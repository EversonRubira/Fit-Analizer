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

## Documentação

- PRDs: [`docs/prd/`](docs/prd/)
- Specs: [`docs/specs/`](docs/specs/)
- Estado do projeto: [`docs/STATUS.md`](docs/STATUS.md)
