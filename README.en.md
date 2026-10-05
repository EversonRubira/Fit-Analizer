<!-- Keep in sync with README.md: any change goes to both versions in the same PR. -->
# Fit Analizer

** Versão em português: [README.md](README.md)**

Fit analyzer between job postings and a structured professional profile. It takes the text of a job posting, compares it against the Profile (skills and experience) and returns a fit percentage, the classified mandatory requirements, the risks and a decision (apply or not).

Personal project for local use, built with Java and Spring Boot on top of the Anthropic API (Claude Haiku 4.5 by default). It has no authentication and is not meant for cloud hosting: the scope is deliberately small.

The project documentation (`docs/`) and the identifiers in the code (classes, decision values, folders) are in Portuguese. This README explains what they mean.

## What this project demonstrates

This project is also meant to show how I work with an LLM inside a system, not just how I call one. Each point below says where to find the evidence in the repository.

**Spec-driven development (SDD).** Every feature starts as a document before it becomes code (`docs/prd/` and `docs/specs/`), and the project state and decisions are recorded in `docs/STATUS.md`. A decision is discussed first and only then implemented, and each decision records its rationale and what was deliberately left out.

**A harness around the model.** The model is not the source of truth. The LLM response goes through checks in code before it is stored:
- `VerificadorEvidencia` (evidence verifier) downgrades to "none" any requirement marked as covered whose evidence does not actually exist in the Profile (protection against hallucination).
- `VerificadorAnosMinimos` (minimum-years verifier) and `Decisao.aplicarTeto` (apply ceiling) cap the decision (at most `nao_candidatar`, "do not apply") when an eliminatory years-of-experience requirement is not met.
- The model output is always structured JSON (tool use with a schema), never free text.

**Prompt versioning.** The prompt is versioned (currently v4) and each result stores the prompt version and the model that produced it. Deduplication by `(profile, job posting)` takes the version into account: the same text with a different prompt is re-analyzed, and with the same prompt the stored result is returned without spending an API call.

**Human judgment before the system's.** The script `scripts/analisar-vaga.sh` ("analyze job posting") requires me to record my own rating before seeing the system's, and writes both to a local CSV, with a column to note whether I changed my mind. The question I care about is whether the system saves time or catches cases my intuition misses, and that can only be answered if my opinion is written down first. Each disagreement becomes a new regression case.

**Tests that catch what mocks hide.** A real bug (the model returned `[exp:2]` while the verifier expected `exp:2`, which dropped the fit score to zero) passed every mocked test, because the mocks had the references hand-written in the expected format. It only showed up in the prompt regression suite, which runs against the real API, separately from the normal tests.

## How it works

The fit score is computed as `(sum of the weights of the mandatory requirements / total mandatory requirements) x 100`, with strong evidence = 1.0, partial = 0.5 and no evidence = 0. The result is always rounded down.

| Fit score | Decision | Meaning |
|---|---|---|
| 85 to 100 | `cv_prioritario` | priority application (CV) |
| 70 to 84 | `cv_carta` | CV with cover letter |
| 50 to 69 | `cv_carta_com_aviso` | CV with cover letter, with caveats |
| 30 to 49 | `nao_candidatar` | do not apply |
| 0 to 29 | `fora_escopo` | out of scope |

The model may only cite as evidence something that exists in the Profile data injected into the prompt. Requirements the Profile cannot prove (education, language level, availability or location) are flagged (`foraDoPerfil`, "outside the profile") and, without evidence, raise the review flag (`revisar`), without capping the decision.

## Architecture decisions

- **Personal, local use.** No authentication, rate limiting or hosting.
- **No RAG.** The whole Profile fits in the prompt, so embedding-based retrieval would add complexity with no benefit.
- **No in-house job collector.** Job postings come in pasted by hand or through an external agent, via the same endpoint. A collector only gets built if real friction justifies it.
- **No hidden retries.** The SDK client uses `maxRetries(0)`: by default the SDK would retry up to twice, and each retry resends the whole request, which could multiply the cost. API errors reach the HTTP client as 502.
- **Defense in depth against duplicates.** A `UNIQUE (profile_id, vaga_chave)` constraint in the database (the key is the job posting URL or, without one, the hash of the normalized text), a check in the service before calling the API, and handling of the race condition through `DataIntegrityViolationException`, tested with Testcontainers.
- **Hashimoto principle.** No abstraction or field is created before there is friction asking for it (for example, the education field is left out of the Profile on purpose).

## How to run

Requirements: JDK 21, Docker, `curl`, `jq` and an Anthropic API key in the `ANTHROPIC_API_KEY` environment variable.

```bash
scripts/subir.sh                      # starts Postgres (fit-pg container) and the application
scripts/analisar-vaga.sh <rating> -   # paste the job posting and finish with Ctrl+D
scripts/parar.sh                      # stops the application (the database persists)
scripts/backup-profile.sh             # saves the Profile as JSON outside the repository
```

The rating is your prediction before seeing the result, and must be one of the values in the decision table. Without `-`, `analisar-vaga.sh` opens `$EDITOR` so you can paste the job posting; it also accepts a file, `--url <id-or-link>` and `--frente TECH|COMEX` (career track). The history is kept in `~/fit-analises.csv`, outside the repository. The scripts default to `OWNER=everson` and `API_URL=http://localhost:8080`.

The API has two resources: `/profiles` (the Profile, with `/profiles/{owner}/skills` and `/profiles/{owner}/experiencias`) and `POST /matches` (the analysis). Example with fictitious data:

```bash
curl -s -X POST localhost:8080/profiles -H 'Content-Type: application/json' \
  -d '{"owner":"teste","bio":"Fictitious profile."}'
curl -s -X POST localhost:8080/profiles/teste/skills -H 'Content-Type: application/json' \
  -d '{"nome":"Java","anosExperiencia":2,"frente":"TECH"}'
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8080/matches -H 'Content-Type: application/json' \
  -d '{"owner":"teste","frente":"TECH","textoVaga":"EXAMPLE. Mandatory requirements: Java, Kafka."}'
```

The first analysis returns 201 and spends one API call; repeating the same job posting returns 200 with the stored result. The API key never goes in the request: the application reads it from the environment. The model can be changed with `CLAUDE_MODEL`, and the database connection with `DB_URL`, `DB_USER` and `DB_PASSWORD`.

## Tests

```bash
./mvnw test
```

Runs the normal suite, with no API calls and no cost, including integration tests with Testcontainers (they need Docker) and a guard that prevents accidental commits of real job postings. GitHub Actions runs the same command on every PR and every push to `main`.

### Prompt regression

Runs against the real API and spends credit (one call per job posting), so it is excluded from `mvn test` and from CI:

```bash
./mvnw test -Dgroups=regressao -DexcludedGroups= -Dtest=RegressaoPromptTest
```

It fails if the system's decision is 2 or more bands away from the expected decision; a 1-band difference is tolerated. Without `ANTHROPIC_API_KEY`, or with any empty value in `esperado.properties` (expected decisions), the test is skipped instead of failing.

The cases use fixtures with third-party job postings and a real profile, which must **never** go into `src/test/resources`. They live in `regressao-local/` (ignored by git): `profile-teste.json`, `vaga-NN.txt` and `esperado.properties`. If `regressao-local/esperado.properties` exists, all fixtures come from that folder; otherwise, all of them come from the versioned examples, which start with "EXEMPLO". The two sources are never mixed: a file missing from the local folder is an error. You can point to another folder with `-Dregressao.dir="$HOME/other-folder"`, which must contain `esperado.properties`, otherwise the test fails. The first line of the output says which source was used. `RegressaoFixturesTest` is part of `mvn test` and fails if any versioned `vaga-NN.txt` does not start with "EXEMPLO".

## Known limitations

- "Senior" without an explicit number of years does not trigger the decision cap.
- Requirements outside the Profile, such as location or remote work, count in the denominator as zero and pull the fit score down.
- A second LLM layer reviewing the analysis (preferably a different model) is recorded in the PRD as a next phase, with a defined trigger, but is not implemented.
- The regression set has only 5 cases, so conclusions about the system's usefulness are provisional.

## Stack

Java 21, Spring Boot 3.3, PostgreSQL 16, Testcontainers, JUnit, Anthropic Java SDK (Claude Haiku 4.5).

## Author

Everson Rubira. [GitHub](https://github.com/EversonRubira) | [LinkedIn](https://linkedin.com/in/eversonrubira)
