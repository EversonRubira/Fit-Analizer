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
