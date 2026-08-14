# Spec — F01: Cadastro de Perfil Técnico Estruturado

**PRD de referência:** `docs/prd/F01-cadastro-perfil-tecnico.md`
**Status:** Rascunho para implementação
**Data:** 2026-08-14

## 1. Arquitetura da Feature

### 1.1 Camadas e responsabilidades

| Camada | Faz | Não faz |
|---|---|---|
| **Controller** (`ProfileController`) | Recebe a requisição HTTP, desserializa o corpo no DTO correspondente, delega ao Service, devolve o DTO de resposta com o status HTTP de sucesso. | Não contém regra de negócio (ex: não decide se um `owner` já existe). Não acessa `Repository`/`Entity` diretamente. Não decide status de erro — isso é responsabilidade do exception handler global (seção 1.3). |
| **DTOs** (`ProfileCreateRequest`, `ProfileUpdateRequest`, `ProfileResponse`) | Definem o contrato de entrada/saída da API. Carregam anotações de validação de formato (Bean Validation: ex. `@NotBlank` em `owner` na criação). `ProfileUpdateRequest` tem todos os campos opcionais (nulos = "não alterar"), refletindo a atualização parcial definida no PRD. | Não contêm lógica de negócio. Não são a mesma classe que a `Entity` — não há mapeamento automático de DTO para tabela. |
| **Service** (`ProfileService`) | Contém toda a regra de negócio: bloquear criação duplicada, aplicar merge parcial na atualização (só sobrescreve campos não nulos do DTO), lançar erro de não encontrado. Orquestra chamadas ao `Repository`. | Não conhece HTTP — não recebe `HttpServletRequest`, não devolve `ResponseEntity`, não lança `HttpStatus`. Lança exceções de domínio (`ProfileAlreadyExistsException`, `ProfileNotFoundException`), que são mapeadas para status HTTP em outra camada. |
| **Repository** (`ProfileRepository`, interface Spring Data JPA) | Acesso a dados: `findByOwner`, `existsByOwner`, `save`, `deleteByOwner`. | Não contém regra de negócio. Não decide se um "não encontrado" é um erro — apenas retorna `Optional.empty()`/`false`; quem decide o que fazer com isso é o `Service`. |
| **Entity** (`ProfileEntity`) | Mapeamento JPA da tabela `profile` no PostgreSQL (campos `owner`, `bio`, `skills`, `historicoProfissional`). | Sem lógica de negócio além de getters/setters e equals/hashCode. Não é exposta diretamente na API (nunca é o tipo de retorno de um endpoint). |
| **Exception Handler** (`@RestControllerAdvice`) | Único lugar que traduz exceções (de validação, de domínio, de persistência ou não mapeadas) em respostas HTTP com status e corpo de erro padronizados. | Não contém regra de negócio — só faz o mapeamento exceção → resposta HTTP. |

### 1.2 Fluxo de uma requisição típica (`PATCH /profiles/{owner}`)

1. **Controller** recebe o `PATCH`, desserializa o corpo em `ProfileUpdateRequest` (campos opcionais).
2. **Bean Validation** roda antes do método do Controller (via `@Valid`), checando formato dos campos presentes (ex: se `skills` foi enviado, cada item precisa ter `nome` não vazio e `anosExperiencia >= 0`). Se falhar, lança `MethodArgumentNotValidException` — a requisição nunca chega ao Service.
3. **Controller** chama `profileService.update(owner, request)`.
4. **Service** busca a entidade via `profileRepository.findByOwner(owner)`. Se vazio, lança `ProfileNotFoundException`.
5. **Service** aplica o merge parcial: para cada campo do DTO que não é nulo, sobrescreve o campo correspondente na entidade; campos nulos no DTO mantêm o valor atual da entidade.
6. **Service** chama `profileRepository.save(entity)` — o Hibernate emite o `UPDATE` no PostgreSQL.
7. **Service** retorna a entidade atualizada ao Controller.
8. **Controller** converte a entidade em `ProfileResponse` e devolve `200 OK`.

Se qualquer exceção for lançada nos passos 2, 4 ou 6, o fluxo normal é interrompido e cai no exception handler (seção 1.3) — o Controller não trata erro em nenhum ponto desse fluxo.

### 1.3 Onde cada erro é tratado

| Tipo de erro | Onde é detectado | Onde é tratado/mapeado | Status HTTP |
|---|---|---|---|
| Validação de entrada (formato: campo obrigatório ausente, tipo inválido, valor fora de faixa) | Bean Validation, disparada a partir do DTO anotado (`@Valid` no Controller) | `@ExceptionHandler(MethodArgumentNotValidException.class)` no `@RestControllerAdvice` | 400 |
| Regra de negócio: criar perfil para `owner` que já existe | `ProfileService.create` | `@ExceptionHandler(ProfileAlreadyExistsException.class)` no `@RestControllerAdvice` | 409 |
| Regra de negócio: consultar/atualizar/deletar `owner` inexistente | `ProfileService.get` / `update` / `delete` | `@ExceptionHandler(ProfileNotFoundException.class)` no `@RestControllerAdvice` | 404 |
| Erro de persistência (falha de conexão com PostgreSQL, violação de constraint no banco) | `ProfileRepository` / Hibernate, propaga como `DataAccessException` | `@ExceptionHandler(DataAccessException.class)` no `@RestControllerAdvice`, logado com stack trace | 500 |
| Erro não mapeado (bug, `NullPointerException`, etc.) | Qualquer camada | `@ExceptionHandler(Exception.class)` (catch-all) no `@RestControllerAdvice`, logado com stack trace completo | 500 |

**Regra prática para debugar em produção:** ao ver uma exceção,
- se é `MethodArgumentNotValidException` → o problema é de contrato/formato, olhar o DTO e a requisição recebida;
- se é uma exceção de domínio nomeada (`ProfileNotFoundException`, `ProfileAlreadyExistsException`) → o problema é de regra de negócio, olhar o `ProfileService`;
- se é `DataAccessException` (ou subclasse) → o problema é de persistência/banco, olhar `ProfileRepository`, conexão com PostgreSQL, ou constraints da tabela;
- qualquer outra exceção → é um bug não previsto, olhar o stack trace completo para achar a camada de origem.

### 1.4 Justificativa arquitetural

A arquitetura é deliberadamente simples: `Controller → Service → Repository/Entity`, com DTOs no limite da API e um exception handler global. Isso é proporcional ao escopo do F01 porque:

- **Uma única entidade, sem relacionamentos complexos.** O perfil técnico é uma entidade isolada (`skills` e `historicoProfissional` são listas embutidas, não entidades relacionadas com ciclo de vida próprio). Não há agregados com invariantes cruzando múltiplas entidades que justifiquem DDD tático (Aggregates, Value Objects, Domain Events).
- **Um único mecanismo de persistência, sem necessidade de trocar implementação.** O PRD fixa PostgreSQL via JPA/Hibernate como decisão já tomada, reaproveitando stack conhecida — não há requisito de suportar múltiplos bancos ou fontes de dados simultâneas.
- **Um único adapter de entrada.** Só existe REST API (PRD, seção 9: sem UI em v1). Não há necessidade de abstrair a entrada por trás de uma porta para suportar múltiplos protocolos (REST + gRPC + CLI, por exemplo).
- **Regra de negócio simples e já enumerável.** As únicas regras são: unicidade de perfil por `owner`, atualização parcial, e "não encontrado" para operações sobre `owner` inexistente — cabem confortavelmente numa única classe de serviço, sem necessidade de separar em múltiplos casos de uso/handlers.

Introduzir arquitetura hexagonal (ports & adapters) ou DDD tático agora adicionaria camadas de indireção (interfaces de porta, mappers extras entre domínio e persistência) sem nenhum problema real que resolvam hoje. Seguindo o princípio Hashimoto — complexidade se adiciona quando há fricção observada, não antecipada — essa estrutura em camadas simples é o ponto de partida certo, e deve ser revisitada apenas se/quando a fricção aparecer de fato.

**Observação (não implementar agora):** o PRD (seção 8, Consumes/Provides) já prevê que a futura feature de Fit Matching (F02) consumirá o perfil estruturado produzido aqui. Se essa integração futura exigir múltiplas fontes de perfil, cache, ou desacoplar o consumidor da persistência concreta, pode fazer sentido introduzir uma interface de porta explícita para o acesso ao perfil naquele momento. Hoje, a interface `ProfileRepository` do Spring Data JPA já cumpre esse papel de abstração mínima — não há necessidade de camada adicional.
