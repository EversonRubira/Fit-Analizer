<!-- Manter em sincronia com README.en.md: qualquer mudança aqui vai para as duas versões no mesmo PR. -->
# Fit Analizer

** English version: [README.en.md](README.en.md)**

Analisador de aderência entre vagas de emprego e um perfil profissional estruturado. Recebe o texto de uma vaga, compara-o com o Profile (competências e experiências) e devolve uma percentagem de aderência, os requisitos obrigatórios classificados, os riscos e uma decisão (candidatar ou não).

Projeto pessoal, de uso local, construído em Java e Spring Boot com a API da Anthropic (Claude Haiku 4.5 por omissão). Não tem autenticação nem está pensado para alojamento em nuvem: o âmbito é deliberadamente pequeno.

## O que esse projeto demonstra

Este projeto serve também para mostrar como trabalho com um LLM dentro de um sistema, e não apenas como o chamo. Os pontos abaixo dizem onde procurar a evidência no repositório.

**Desenvolvimento orientado por especificação (SDD).** Cada funcionalidade nasce como um documento antes de virar código (`docs/prd/` e `docs/specs/`), e o estado do projeto e as decisões ficam registados em `docs/STATUS.md`. A decisão é discutida primeiro, só depois vira implementação, e cada decisão regista o motivo e o que foi deixado de fora.

**Um harness à volta do modelo.** O modelo não é a fonte de verdade. A resposta do LLM passa por verificações em código antes de ser guardada:
- `VerificadorEvidencia` rebaixa para "nenhum" qualquer requisito dado como coberto cuja evidência não exista de facto no Profile (proteção contra alucinação).
- `VerificadorAnosMinimos` e `Decisao.aplicarTeto` impõem um teto à decisão (no máximo `nao_candidatar`) quando um requisito eliminatório de anos de experiência não é cumprido.
- A saída do modelo é sempre JSON estruturado (tool use com esquema), nunca texto livre.

**Versionamento de prompt.** O prompt tem versão (atualmente v4) e cada resultado guarda a versão e o modelo que o produziram. A deduplicação por `(profile, vaga)` considera a versão: o mesmo texto com um prompt diferente é reanalisado, e com o mesmo prompt devolve o resultado guardado sem gastar chamada à API.

**Avaliação humana antes do sistema.** O script `scripts/analisar-vaga.sh` exige que eu registe a minha nota antes de ver a do sistema, e grava ambas num CSV local, com uma coluna para anotar se mudei de ideia. A pergunta que me interessa é se o sistema poupa tempo ou apanha casos que a intuição falha, e só dá para a responder se a minha opinião ficar escrita antes. Cada discordância passa a ser um novo caso de regressão.

**Testes que apanham o que os mocks escondem.** Um bug real (o modelo devolvia `[exp:2]` e o verificador esperava `exp:2`, o que zerava a aderência) passou em todos os testes com mock, porque neles as referências eram escritas à mão já no formato certo. Só apareceu na suite de regressão de prompt, que corre contra a API verdadeira, separada dos testes normais.

## Como funciona

A aderência é calculada como `(soma dos pesos dos requisitos obrigatórios / total de requisitos obrigatórios) x 100`, com evidência forte = 1,0, parcial = 0,5 e sem evidência = 0. O resultado é sempre arredondado para baixo.

| Aderência | Decisão |
|---|---|
| 85 a 100 | `cv_prioritario` |
| 70 a 84 | `cv_carta` |
| 50 a 69 | `cv_carta_com_aviso` |
| 30 a 49 | `nao_candidatar` |
| 0 a 29 | `fora_escopo` |

O modelo só pode citar como evidência algo que exista nos dados do Profile injetados no prompt. Requisitos que o Profile não consegue provar (formação, idioma, disponibilidade ou localização) ficam marcados (`foraDoPerfil`) e, sem evidência, ligam o aviso de revisão (`revisar`), sem impor teto à decisão.

## Decisões de arquitetura

- **Uso pessoal, local.** Sem autenticação, limitação de pedidos ou alojamento.
- **Sem RAG.** O Profile cabe inteiro no prompt, por isso recuperação por embeddings seria complexidade sem benefício.
- **Sem coletor de vagas próprio.** A vaga entra colada à mão ou por um agente externo, através do mesmo endpoint. Só se constrói um coletor se a fricção real o justificar.
- **Sem retries escondidos.** O cliente da SDK está com `maxRetries(0)`: por omissão a SDK repetiria até duas vezes, e cada repetição reenvia o pedido inteiro, o que podia multiplicar o custo. Erros da API chegam ao cliente HTTP como 502.
- **Defesa em profundidade contra duplicados.** Restrição `UNIQUE (profile_id, vaga_chave)` na base de dados (a chave é a URL da vaga ou, sem ela, o hash do texto normalizado), verificação no serviço antes de chamar a API e tratamento da condição de corrida com `DataIntegrityViolationException`, testada com Testcontainers.
- **Princípio de Hashimoto.** Não se cria abstração ou campo antes de haver fricção que o peça (por exemplo, o campo de formação está fora do Profile de propósito).

## Como correr

Requisitos: JDK 21, Docker, `curl`, `jq` e uma chave da API da Anthropic na variável de ambiente `ANTHROPIC_API_KEY`.

```bash
scripts/subir.sh                      # arranca o Postgres (container fit-pg) e a aplicação
scripts/analisar-vaga.sh <nota> -     # cola a vaga e termina com Ctrl+D
scripts/parar.sh                      # pára a aplicação (a base de dados persiste)
scripts/backup-profile.sh             # guarda o Profile num JSON fora do repositório
```

A nota é a sua previsão antes de ver o resultado, e tem de ser um dos valores da tabela de decisões. Sem `-`, o `analisar-vaga.sh` abre o `$EDITOR` para colar a vaga; também aceita um ficheiro, `--url <id-ou-link>` e `--frente TECH|COMEX`. O histórico fica em `~/fit-analises.csv`, fora do repositório. Os scripts usam `OWNER=everson` e `API_URL=http://localhost:8080` por omissão.

A API tem dois recursos: `/profiles` (o Profile, com `/profiles/{owner}/skills` e `/profiles/{owner}/experiencias`) e `POST /matches` (a análise). Exemplo com dados fictícios:

```bash
curl -s -X POST localhost:8080/profiles -H 'Content-Type: application/json' \
  -d '{"owner":"teste","bio":"Perfil fictício."}'
curl -s -X POST localhost:8080/profiles/teste/skills -H 'Content-Type: application/json' \
  -d '{"nome":"Java","anosExperiencia":2,"frente":"TECH"}'
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8080/matches -H 'Content-Type: application/json' \
  -d '{"owner":"teste","frente":"TECH","textoVaga":"EXEMPLO. Requisitos obrigatórios: Java, Kafka."}'
```

A primeira análise responde 201 e gasta uma chamada à API; repetir a mesma vaga responde 200 com o resultado guardado. A chave da API nunca vai no pedido: a aplicação lê-a do ambiente. O modelo pode ser trocado com `CLAUDE_MODEL`, e a ligação à base de dados com `DB_URL`, `DB_USER` e `DB_PASSWORD`.

## Testes

```bash
./mvnw test
```

Corre a suite normal, sem API nem custo, incluindo testes de integração com Testcontainers (precisam de Docker) e uma guarda que impede commits acidentais de vagas reais. O GitHub Actions corre o mesmo comando em cada PR e em cada push para `main`.

### Regressão de prompt

Corre contra a API real e gasta crédito (uma chamada por vaga), por isso está fora do `mvn test` e do CI:

```bash
./mvnw test -Dgroups=regressao -DexcludedGroups= -Dtest=RegressaoPromptTest
```

Falha se a decisão do sistema ficar a 2 faixas ou mais da decisão esperada; 1 faixa de diferença é tolerada. Sem `ANTHROPIC_API_KEY`, ou com algum valor vazio em `esperado.properties`, o teste é ignorado em vez de falhar.

Os casos usam fixtures com vagas de terceiros e perfil real, que **nunca** devem ir para `src/test/resources`. Ficam em `regressao-local/` (ignorada pelo git): `profile-teste.json`, `vaga-NN.txt` e `esperado.properties`. Se `regressao-local/esperado.properties` existir, todas as fixtures vêm dessa pasta; senão, todas vêm dos exemplos versionados, que começam com "EXEMPLO". As duas fontes nunca se misturam: um ficheiro que falte na pasta local é erro. Pode apontar para outra pasta com `-Dregressao.dir="$HOME/outra-pasta"`, que tem de conter `esperado.properties`, senão o teste falha. A primeira linha da saída indica qual fonte foi usada. O `RegressaoFixturesTest` faz parte do `mvn test` e falha se algum `vaga-NN.txt` versionado não começar com "EXEMPLO".

## Limitações conhecidas

- "Senior" sem número de anos explícito não aciona o teto da decisão.
- Requisitos fora do Profile, como localização ou trabalho remoto, entram no denominador com zero e puxam a aderência para baixo.
- Uma segunda camada de LLM como revisor da análise (de preferência outro modelo) está registada no PRD como fase seguinte, com gatilho definido, mas não implementada.
- O conjunto de regressão tem apenas 5 casos, pelo que as conclusões sobre a utilidade do sistema são provisórias.

## Stack

Java 21, Spring Boot 3.3, PostgreSQL 16, Testcontainers, JUnit, SDK Java da Anthropic (Claude Haiku 4.5).

## Autor

Everson Rubira. [GitHub](https://github.com/EversonRubira) | [LinkedIn](https://linkedin.com/in/eversonrubira)
