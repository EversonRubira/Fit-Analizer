# Spec — F01: Cadastro de Perfil Técnico Estruturado

**PRD de referência:** `docs/prd/F01-cadastro-perfil-tecnico.md`
**Status:** Rascunho para implementação
**Data:** 2026-08-14

## 1. Arquitetura

### 1.1 Estilo arquitetural

Arquitetura em camadas simples (Controller → Service → Repository), adaptação do padrão MVC para uma API REST. Não é Clean Architecture nem Hexagonal.

**Justificativa (princípio Hashimoto):** o domínio da F01 é um CRUD de perfil técnico, sem múltiplas implementações concorrentes de nada e sem indicação real de troca de infraestrutura (ex: banco de dados). Introduzir portas/adapters agora pagaria o custo de indireção sem resolver nenhuma fricção existente. A única troca de fornecedor plausível no roadmap do projeto (Claude API → outro provedor de IA) pertence à futura feature de análise de fit, não à F01, e será isolada via interface (`FitAnalysisClient`) apenas quando essa feature for especificada.

Se surgir necessidade real de múltiplas implementações de uma mesma responsabilidade, esse é o gatilho para reavaliar o estilo arquitetural — não antes disso.

### 1.2 Responsabilidade por camada

**Controller**
- Recebe a requisição HTTP e valida formato/sintaxe via Bean Validation nos DTOs (`@NotNull`, `@Size`, etc.).
- Converte DTO de entrada → chamada ao Service.
- Converte retorno do Service → DTO de resposta.
- Captura exceções de negócio lançadas pelo Service via try-catch por endpoint e traduz para o status HTTP correspondente (ex: `ProfileAlreadyExistsException` → 409, `ProfileNotFoundException` → 404).
- Não contém regra de negócio.

**Decisão registrada:** optou-se por try-catch por endpoint em vez de `@ControllerAdvice` global, priorizando visibilidade total do fluxo de erro em cada método durante a fase de aprendizado. **Gatilho de revisão:** se o número de endpoints crescer e o padrão de tratamento começar a se repetir de forma relevante entre eles, migrar para `@ControllerAdvice` centralizado.

**Service**
- Contém toda a regra de negócio da feature.
- Aplica a regra de unicidade "um perfil por owner": verifica via `findByOwner` antes de persistir e lança `ProfileAlreadyExistsException` se já existir.
- Aplica a semântica de PATCH: para cada campo não-nulo no DTO de atualização, sobrescreve o campo correspondente na entidade; campos nulos são ignorados (mantêm o valor atual).
- Delimita a fronteira transacional (`@Transactional`).
- Busca explicitamente qualquer dado relacionado necessário antes de retornar (reforçado por `open-in-view: false`, já configurado no Bloco 0).

**Repository**
- Interface Spring Data JPA, sem lógica além de queries derivadas (ex: `findByOwner`).
- Constraint `UNIQUE` declarada no schema (via entidade/migration) sobre a coluna `owner`, como rede de segurança contra concorrência.

### 1.3 Regra de negócio: unicidade de perfil por owner (409)

Abordagem de defesa em profundidade, com dois mecanismos independentes:

1. **Service** — verificação explícita (`findByOwner`) antes do `save`, lançando `ProfileAlreadyExistsException` de forma legível e testável. Cobre o caso comum.
2. **Banco** — constraint `UNIQUE` na coluna `owner`. Cobre o caso raro de duas requisições concorrentes passarem pela verificação do Service antes de qualquer uma persistir (race condition / TOCTOU). Nesse caso, a segunda operação falha na camada de persistência com `DataIntegrityViolationException`, capturada no Controller e traduzida para 409 — mesmo tratamento e mesmo status HTTP que o caminho do Service, do ponto de vista do cliente da API.

**Nota sobre o risco aceito:** mesmo em uso individual (um único usuário), a race condition não é eliminada — depende de requisições concorrentes ao mesmo recurso, não do número de usuários do sistema (ex: duplo clique, retry automático de cliente, testes manuais paralelos). A constraint no banco existe justamente para cobrir esse cenário de forma garantida, independente da camada de aplicação.

### 1.4 PATCH — atualização parcial

DTO de atualização (`ProfileUpdateDTO`) com todos os campos opcionais. Regra: campo `null` no JSON de entrada significa "não alterar"; campo presente com valor sobrescreve o valor atual na entidade. Implementação direta via Jackson, sem necessidade de JSON Patch (RFC 6902) — não há requisito atual de distinguir "campo omitido" de "campo enviado como null", o que tornaria RFC 6902 uma complexidade desproporcional ao problema.

### 1.5 Rastreabilidade de erro

Toda exceção de negócio lançada pelo Service é uma classe própria e nomeada (ex: `ProfileAlreadyExistsException`, `ProfileNotFoundException`), nunca exceção genérica. Isso garante que o try-catch no Controller mapeie por tipo, não por inferência de mensagem, e que qualquer novo erro futuro seja explícito no código antes de acontecer em produção.

## 2. Bloco 3 — Controller e DTOs (decidido em 2026-09-29)

### 2.1 Endpoints

| Método | Path | Corpo | Sucesso | Erros |
|---|---|---|---|---|
| POST | `/profiles` | `ProfileCreateRequest{owner, bio?}` | 201 | 409 |
| GET | `/profiles/{owner}` | — | 200 | 404 |
| PATCH | `/profiles/{owner}` | `ProfileUpdateRequest{bio?}` | 200 | 404 |
| DELETE | `/profiles/{owner}` | — | 204 | 404 |
| POST | `/profiles/{owner}/skills` | `SkillRequest{nome, anosExperiencia, frente}` | 201 | 404, 409 |
| DELETE | `/profiles/{owner}/skills/{nome}` | — | 204 | 404 |
| POST | `/profiles/{owner}/experiencias` | `ExperienciaRequest{empresa, cargo, frente, dataInicio, dataFim?, tecnologiasUsadas?}` | 201 | 404 |
| PATCH | `/profiles/{owner}/experiencias/{id}` | `ExperienciaUpdateRequest` (todos campos opcionais) | 200 | 404 |
| DELETE | `/profiles/{owner}/experiencias/{id}` | — | 204 | 404 |

`owner` vai no corpo só no `POST /profiles` (ainda não existe recurso identificável na URL nesse momento); em todo o resto é path variable, porque já referencia um recurso existente.

`ExperienciaProfissional` não precisa de checagem de duplicata como `Skill` — tem `id` gerado próprio, e não existe "natural key" única de experiência (a mesma empresa pode aparecer duas vezes em períodos diferentes, legitimamente).

### 2.2 DTOs como `record`

Todos os DTOs (`com.fitanalizer.profile.dto`) são `record`, não `class`: são apenas portadores de dado imutáveis, sem comportamento — o encaixe natural de record no Java 21, menos boilerplate que uma classe com getters manuais.

**Por que Request e Response são classes separadas, mesmo cobrindo o mesmo recurso:** os campos que fazem sentido variam por direção (`ProfileCreateRequest` tem `owner` obrigatório e não tem `id`; `ProfileResponse` tem `id` mas não precisa de `@NotBlank` numa resposta que o próprio sistema gerou). Um DTO único reaproveitado nas duas direções acumula campos ignorados ou anotações de validação sem efeito — mistura duas responsabilidades numa classe só.

**Por que nunca serializar a entidade JPA direto:** três razões concretas, não só "boa prática" abstrata — (1) proxy lazy fora de sessão quebra serialização (ver 2.3); (2) contrato de API não pode ficar acoplado 1:1 ao modelo de persistência (renomear uma coluna não pode forçar o cliente da API a mudar); (3) validação de entrada (`@NotBlank`, `@PositiveOrZero`) é regra de "requisição HTTP válida", não pertence à entidade de domínio.

Mapeamento entidade → DTO de resposta feito via método estático `de(entidade)` no próprio DTO (ex: `SkillResponse.de(skill)`), sem mapper dedicado (MapStruct etc.) — com só 3 DTOs de resposta, uma dependência de mapeamento pagaria indireção sem resolver fricção real (princípio Hashimoto).

### 2.3 Achado durante o desenho: inicialização de coleções LAZY

`Profile.skills` (`@ElementCollection`) e `Profile.historicoProfissional` (`@OneToMany`) são `FetchType.LAZY`. Como o Controller monta o DTO de resposta depois que o método do Service retorna — já fora da transação (`open-in-view: false`, Bloco 0) — acessar essas coleções nesse ponto lançaria `LazyInitializationException`.

Corrigido em `ProfileService.buscarOuFalhar` (chamado por praticamente todo método do Service): `Hibernate.initialize(...)` nas duas coleções antes de retornar a entidade, ainda dentro da transação. Isso não é um padrão novo — é a responsabilidade que a seção 1.2 já atribuía ao Service ("busca explicitamente qualquer dado relacionado necessário antes de retornar"), só que só ficou necessária de fato agora que existe um Controller consumindo essas entidades fora da transação.

**Terceira camada, achada só na revisão:** `tecnologiasUsadas` dentro de cada `ExperienciaProfissional` também é `@ElementCollection(fetch = LAZY)` — um nível mais fundo que `historicoProfissional`, e fácil de esquecer porque não aparece olhando só pra `Profile`. Inicializar a lista de experiências não inicializa a lista de tecnologias de cada experiência dentro dela. `buscarOuFalhar` também itera `historicoProfissional` e inicializa `tecnologiasUsadas` de cada item.

### 2.4 Tratamento de erro de validação (`@Valid`)

O try-catch por endpoint (seção 1.2) não alcança erro de Bean Validation, porque a validação roda antes do corpo do método do Controller (via `@Valid` no parâmetro). Um único `@ExceptionHandler(MethodArgumentNotValidException.class)` dentro do próprio `ProfileController` cobre isso — é local a este Controller, não um `@ControllerAdvice` global, então não contradiz a decisão da seção 1.2; existe só para manter o corpo de erro (`ErrorResponse{message}`) consistente também nos 400s.

### 2.5 Limitação conhecida — PATCH de experiência não reabre emprego encerrado

`ExperienciaUpdateRequest` usa a mesma semântica de PATCH do `bio` (`null` = não altera). Mas `dataFim == null` já é, por si, um estado de domínio válido (emprego atual, ainda em andamento) — não um "campo vazio". Isso significa que não há como usar este PATCH para reverter `dataFim` de volta a `null` (reabrir um emprego marcado como encerrado). Deixado assim de propósito: não há caso de uso real para isso hoje, e resolver exigiria um DTO com wrapper explícito (`Optional`/`JsonNullable`) só para esse único campo — complexidade desproporcional ao problema atual.
