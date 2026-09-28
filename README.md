# Fit-Analizer

Backend Java/Spring Boot que analisa o fit entre uma vaga de emprego e um
perfil técnico estruturado, usando a Claude API.

Uso pessoal (dois usuários), sem autenticação em v1. Ver a seção de segurança
do PRD da F01 antes de expor a API fora de um ambiente privado.

## Features

| Feature | O que faz | Estado |
|---|---|---|
| F01 — Cadastro de Perfil Técnico | CRUD do perfil (skills, histórico profissional, bio) por `owner` | Em implementação (entidades prontas, schema validado) |
| F02 — Fit Matching | Analisa uma vaga contra o perfil via Claude API e persiste o resultado | PRD concluído |
| F03 — Ranking | Consulta e ordena os resultados da F02 | Planejada |

Um coletor de vagas (projeto separado) vai enviar vagas de APIs públicas de
emprego para o endpoint da F02.

Estado detalhado, decisões e próximos passos: [`docs/STATUS.md`](docs/STATUS.md).

## Stack

- Java 21
- Spring Boot 3.3 (Web, Data JPA, Validation)
- PostgreSQL
- Maven (wrapper incluído)

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

Segredos (senha do banco, futura chave da Claude API) ficam só em variáveis de
ambiente, nunca em arquivos do repositório. `.env` e `application-local.*` já
estão no `.gitignore`.

## Documentação

- PRDs: [`docs/prd/`](docs/prd/)
- Specs: [`docs/specs/`](docs/specs/)
- Estado do projeto: [`docs/STATUS.md`](docs/STATUS.md)
