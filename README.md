# Fit-Analizer

Backend Java/Spring Boot que analisa o fit entre uma vaga de emprego e um
perfil técnico estruturado, usando a Claude API.

Uso pessoal (dois usuários), sem autenticação em v1. Ver a seção de segurança
do PRD da F01 antes de expor a API fora de um ambiente privado.

## Features

| Feature | O que faz | Estado |
|---|---|---|
| F01 — Cadastro de Perfil Técnico | CRUD do perfil (skills, histórico profissional, bio) por `owner` | Implementada e testada |
| F02 — Fit Matching | Analisa uma vaga contra o perfil via Claude API e persiste o resultado (`POST /matches`, com dedup por vaga) | Implementada, testada e validada com chamada real à Claude API |
| F03 — Ranking | Consulta e ordena os resultados da F02 | Planejada |

Um coletor de vagas (projeto separado) vai enviar vagas de APIs públicas de
emprego para o endpoint da F02.

Estado detalhado, decisões e próximos passos: [`docs/STATUS.md`](docs/STATUS.md).

## Stack

- Java 21
- Spring Boot 3.3 (Web, Data JPA, Validation)
- PostgreSQL
- SDK oficial da Claude API para Java (modelo padrão: Haiku 4.5)
- Maven (wrapper incluído)
- Testes: JUnit, Mockito, Testcontainers (Postgres real)
- CI: GitHub Actions

Arquitetura em camadas simples: Controller → Service → Repository.

## Como rodar

Pré-requisitos: JDK 21 e um PostgreSQL acessível.

A conexão é lida de variáveis de ambiente. Sem elas, a aplicação usa os
valores padrão abaixo (banco local):

| Variável | Padrão |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/fit_analizer` |
| `DB_USER` | `fit_analizer` |
| `DB_PASSWORD` | `fit_analizer` |

Para usar os padrões, crie o usuário e o banco locais:

```sql
CREATE USER fit_analizer WITH PASSWORD 'fit_analizer';
CREATE DATABASE fit_analizer OWNER fit_analizer;
```

Depois:

```bash
./mvnw spring-boot:run
```

O schema é gerado pelo Hibernate (`ddl-auto: update`). Esse modo só adiciona
tabelas, colunas e constraints; nunca remove nem renomeia. Depois de mudar uma
constraint, recrie o banco local.

Para a F02 chamar a Claude de verdade, defina `ANTHROPIC_API_KEY` no ambiente.
O modelo pode ser trocado com `CLAUDE_MODEL` (padrão: `claude-haiku-4-5`).

Segredos (senha do banco, chave da Claude API) ficam só em variáveis de
ambiente, nunca em arquivos do repositório. `.env` e `application-local.*` já
estão no `.gitignore`.

## Teste manual da F02 (com a Claude API de verdade)

Cada análise custa cerca de meio centavo de dólar (Haiku 4.5). Use sempre um
perfil fictício, nunca dados reais.

```bash
# Postgres local (mesmos dados de acesso dos padrões acima)
docker run -d --name fit-pg -e POSTGRES_USER=fit_analizer -e POSTGRES_PASSWORD=fit_analizer \
  -e POSTGRES_DB=fit_analizer -p 5432:5432 postgres:16-alpine

# A chave sem ficar no histórico do terminal; depois suba a aplicação
read -s ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY
./mvnw spring-boot:run
```

Em outro terminal, cadastre um perfil e envie uma vaga:

```bash
curl -s -X POST localhost:8080/profiles -H 'Content-Type: application/json' \
  -d '{"owner":"teste","bio":"Dev backend em formação."}'
curl -s -X POST localhost:8080/profiles/teste/skills -H 'Content-Type: application/json' \
  -d '{"nome":"Java","anosExperiencia":2,"frente":"TECH"}'
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8080/matches -H 'Content-Type: application/json' \
  -d '{"owner":"teste","frente":"TECH","vagaUrl":"https://exemplo.com/vaga-1","textoVaga":"Backend Developer Java. Requisitos obrigatorios: Java, Kafka."}'
```

A primeira chamada responde **201**; repetir a mesma responde **200** com o
mesmo resultado, sem nova chamada à API (dedup).

## Testes e CI

```bash
./mvnw test
```

Os testes não precisam de `ANTHROPIC_API_KEY` (o cliente da Claude é mockado),
mas o `MatchDedupIntegrationTest` e o `ProfileConcorrenciaIntegrationTest`
sobem um PostgreSQL real via Testcontainers, então **exigem Docker rodando**.
Com Docker Engine 29 ou mais novo, é preciso o Testcontainers 2.x, que o
`pom.xml` já fixa.

O GitHub Actions (`.github/workflows/ci.yml`) roda `./mvnw test` em todo PR e
em todo push para `main`.

## Analisar vagas reais

O fluxo do dia a dia são três comandos:

```bash
scripts/subir.sh                    # Postgres (container fit-pg) + aplicação, se não estiverem no ar
scripts/analisar-vaga.sh cv_carta   # abre o editor: cole a vaga, grave e feche
scripts/parar.sh                    # para só a aplicação; o Postgres e os dados ficam
```

- **Decida antes de rodar.** A nota (`cv_prioritario`, `cv_carta`,
  `cv_carta_com_aviso`, `nao_candidatar` ou `fora_escopo`) é o primeiro
  argumento e é validada antes de a vaga ser lida. Registar a sua decisão antes
  de ver a do sistema é o que permite medir depois se ele acerta; quando ele
  discorda, o script avisa a distância em faixas.
- **Origem da vaga:** sem origem abre o `$EDITOR` (`nano` por padrão) num
  temporário privado em `/tmp`, apagado no fim; `-` lê do stdin (cole e
  Ctrl+D); ou passe um arquivo. Opções: `--url <id-ou-link>` e
  `--frente TECH|COMEX` (padrão `TECH`).
- **Limpeza antes de enviar:** remove URLs (de links markdown fica só o texto
  visível), espaços no fim das linhas e linhas vazias repetidas, e diz quantos
  caracteres saíram. Texto acima de 15000 caracteres (`VAGA_MAX_CHARS`, igual
  ao limite do servidor) é recusado sem chamar a API.
- **Custo e dedup:** cada análise nova chama a Claude e gasta crédito
  (HTTP 201). Com `--url`, repetir o mesmo link devolve o resultado salvo, sem
  custo (HTTP 200). Sem `--url`, o servidor identifica a vaga pelo texto
  (em minúsculas, espaços colapsados): colar exatamente a mesma vaga também
  devolve o salvo, mas qualquer diferença real no texto é uma análise nova.
  Mudar `prompt-version` reanalisa cada vaga uma vez.
- **Fora do repositório:** o histórico vai para `~/fit-analises.csv` (colunas
  `data, id, minha_nota, nota_sistema, aderencia, revisar, distancia,
  mudei_de_ideia, titulo`; `id` é o `--url` ou `hash:` + 8 caracteres do
  texto limpo, só local). O texto da vaga nunca é impresso nem gravado; só a
  primeira linha, como título. Um CSV no formato antigo é migrado
  automaticamente (cópia em `~/fit-analises.csv.antes-da-migracao`).
- **`subir.sh`:** idempotente. Cria o container `fit-pg` (volume
  `fit-pg-data`) se não existir, inicia-o se estiver parado, espera o banco e
  sobe a aplicação em segundo plano (log em `/tmp/fit-app.log`). Avisa se
  `ANTHROPIC_API_KEY` não estiver definida e se o Profile não existir; não cria
  dados.
- **Backup do Profile (opcional):** `scripts/backup-profile.sh` grava em
  `~/fit-profile-backup-AAAA-MM-DD.json`.
- **Padrões:** `OWNER=everson`, `API_URL=http://localhost:8080`. Requer
  `docker`, `curl` e `jq`.

## Conjunto de regressão do prompt (gasta crédito)

Teste manual que manda vagas reais, julgadas à mão, para a Claude API de
verdade e falha se a decisão obtida ficar a 2 faixas ou mais da esperada
(1 faixa de diferença é tolerada). Serve para pegar uma mudança no prompt que
piorou a análise. Fica **fora** do `./mvnw test` normal e do CI.

**Fixtures reais ficam em `regressao-local/`** na raiz do projeto, ignorada
pelo git: `profile-teste.json`, `vaga-01.txt` a `vaga-05.txt` e
`esperado.properties` (decisão esperada de cada vaga). O repositório é
público: vagas de terceiros, as suas decisões e o seu Profile **nunca** vão
para `src/test/resources/`. Lá ficam só os exemplos fictícios, e o
`RegressaoFixturesTest` quebra o CI se algum `vaga-NN.txt` versionado não
começar com `EXEMPLO`.

- Se `regressao-local/esperado.properties` existir, **todas** as fixtures vêm
  dessa pasta; senão, todas vêm dos exemplos versionados. Nunca mistura: um
  arquivo que falte na pasta local é erro.
- Outra pasta: `-Dregressao.dir=<caminho>` (tem de ter `esperado.properties`,
  senão o teste falha em vez de usar os exemplos).
- A primeira linha da rodada diz qual fonte foi usada e o caminho.
- Sem `ANTHROPIC_API_KEY` ou com algum valor vazio em `esperado.properties`, o
  teste é ignorado, não falha.

```bash
read -s ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY
./mvnw test -Dgroups=regressao -DexcludedGroups= -Dtest=RegressaoPromptTest
./mvnw test -Dgroups=regressao -DexcludedGroups= -Dtest=RegressaoPromptTest -Dregressao.dir="$HOME/outra-pasta"
```

**Migração (uma vez, se as fixtures reais ainda estão em
`src/test/resources/regressao/` com `skip-worktree`)**, nesta ordem:

1. Copie as reais para a pasta local **antes de qualquer outra coisa**:
   `mkdir -p regressao-local && cp src/test/resources/regressao/* regressao-local/`
2. `git update-index --no-skip-worktree src/test/resources/regressao/*`
3. `git checkout -- src/test/resources/regressao` (restaura os exemplos)
4. `git status`: deve estar limpo, sem `regressao-local/` (ignorada). Até o
   passo 3, o `./mvnw test` local falha na guarda, e isso é esperado.

Cada rodada faz cerca de 5 chamadas à API (uma por vaga). Rode sempre que o
prompt mudar e incremente `fitanalizer.claude.prompt-version`. Cada erro novo
da Claude numa vaga real vira um caso novo: um `vaga-NN.txt` e uma linha em
`esperado.properties` em `regressao-local/`, e o nome na lista `CASOS` do
`RegressaoPromptTest`.

## Documentação

- PRDs: [`docs/prd/`](docs/prd/)
- Specs: [`docs/specs/`](docs/specs/)
- Estado do projeto: [`docs/STATUS.md`](docs/STATUS.md)
