# Status do projeto — Fit Analizer

**Atualizado em:** 2026-09-30

## Visão geral

| Item | PRD | Spec | Implementação |
|---|---|---|---|
| F01 — Cadastro de Perfil Técnico | Concluído | Concluída | Blocos 0, 1, 2 e 3 na main, testados (20/20, `mvn test` local); `Frente` mergeada e testada |
| F02 — Fit Matching | Concluído | Concluída (2026-09-30) | Implementada, mergeada (PRs #19, #20 e #23) e validada com chamada real à Claude API (2026-10-01) |
| F03 — Ranking | Não iniciado | — | — |
| F04 — Geração de CV | Não iniciado | — | — |
| Coletor de vagas (projeto externo) | Não iniciado | — | — |

## F01 — Cadastro de Perfil Técnico

Documentos: `docs/prd/F01-cadastro-perfil-tecnico.md`, `docs/specs/F01-cadastro-perfil-tecnico.md`.

**Andamento**
- Bloco 0 (setup) e Bloco 1 (entidades JPA): concluídos e mergeados na main.
- Bloco 2 (Repository/Service): implementado conforme o desenho abaixo.
  **Não compilado nem testado neste ambiente** — o sandbox da sessão não
  alcança o Maven Central (só PyPI/npm/etc. liberados no proxy), diferente
  da validação de schema (que rodou Postgres local dentro do container).
  Rodar `mvn test` localmente antes de mergear.
- **Bloco 3 (Controller/DTOs): mergeado e testado (2026-09-29).** Desenho
  completo na Spec seção 2. Endpoints de perfil (CRUD), skills
  (add/remove) e **experiências profissionais** (add/update/remove —
  decisão nova desta sessão, não ficou pendente). DTOs como `record` em
  `com.fitanalizer.profile.dto`, nunca a entidade JPA exposta direto.
  `mvn test` local (Codespaces): 20/20 passando (8 `ProfileControllerTest`
  + 12 `ProfileServiceTest`).
- **Achado durante o desenho do Bloco 3:** `Profile.skills`,
  `Profile.historicoProfissional` **e** `tecnologiasUsadas` (dentro de cada
  `ExperienciaProfissional`, um nível mais fundo — achado só na revisão) são
  coleções `LAZY`, e o Controller monta o DTO de resposta fora da transação
  do Service. Sem correção, isso quebraria com
  `LazyInitializationException`. Corrigido com `Hibernate.initialize(...)`
  nas três, dentro de `ProfileService.buscarOuFalhar`, ainda dentro da
  transação — detalhe completo na Spec, seção 2.3.
- **Limitação conhecida:** o PATCH de experiência não consegue reverter
  `dataFim` de volta a `null` (reabrir um emprego marcado como encerrado),
  porque `null` nesse campo já é um estado de domínio válido (emprego
  atual), não "campo vazio" — mesma ambiguidade seria resolvida com
  `Optional`/`JsonNullable`, mas não há caso de uso real hoje pra
  justificar essa complexidade. Detalhe na Spec, seção 2.5.
- **Lacuna conhecida (achada em 2026-10-01):** a resposta do
  `POST /profiles/{owner}/experiencias` devolve `"id": null`, porque o DTO é
  montado antes de o Hibernate gravar (o id só nasce no INSERT). O dado está
  certo no banco (`GET /profiles/{owner}` mostra o id), mas o cliente não
  consegue editar nem apagar a experiência sem consultar o perfil antes.
  Não afeta a F02 (o `/matches` recarrega o perfil do banco). Corrigir
  quando o front precisar desse id.
- **Estratégia de teste do Bloco 3:** teste orientado a risco, não cobertura
  de 100%. `ProfileServiceTest` (Mockito) continua cobrindo regra de
  negócio; `ProfileControllerTest` novo (`@WebMvcTest`/MockMvc) cobre só a
  costura HTTP → DTO → Service → HTTP nos endpoints representativos (status
  code, validação `@Valid` retornando 400) — não é exaustivo por endpoint,
  é prova de que a fiação funciona. Getters/setters e DTOs sem lógica não
  têm teste próprio, de propósito.

**Validação de schema (pendência antes do Bloco 2): resolvida.** Feita com
PostgreSQL 16 local, dentro do container da sessão. A tentativa no Supabase foi
abandonada porque o container não tem rota de rede para a porta 5432. O schema
gerado pelo `ddl-auto: update` bate com o desenho:
- `profiles`: `id` identity, `bio varchar(2000)`, `owner NOT NULL` com UNIQUE.
- `profile_skills` (`@ElementCollection`): FK para `profiles`, sem PK própria.
- `professional_experiences`: FK `profile_id` para `profiles`, `data_fim`
  nullable (emprego atual).
- `experience_technologies`: FK `experiencia_id` para `professional_experiences`.

Achados da validação:
- **Nenhuma FK tem `ON DELETE CASCADE`.** A exclusão de filhos depende do
  Hibernate (`CascadeType.ALL` + `orphanRemoval` e `@ElementCollection`). O
  delete de Profile precisa carregar a entidade e chamar
  `repository.delete(profile)`. Um delete em massa via JPQL
  (`@Modifying @Query("delete from Profile ...")`) não passa pelo Hibernate e
  quebraria na FK. Um `deleteByOwner` **derivado** do Spring Data é seguro,
  porque carrega cada entidade e remove uma a uma.
- **`ddl-auto: update` só adiciona, nunca remove.** Ao renomear a UNIQUE, o
  banco existente ficou com a constraint antiga e a nova ao mesmo tempo; foi
  preciso recriar o banco. Mudanças de constraint em banco com dados reais vão
  exigir migração manual (ou ferramenta de migração).

**Modelo de `Frente` (comex/tech): decidido (2026-09-29).** Contexto: Everson
candidata-se em duas frentes com prioridade igual (comex e tech), e o
Profile hoje é "cego" a frente — nada distingue a que frente uma skill ou
experiência pertence. Isso importa porque a F02 precisa julgar cada vaga só
contra a frente certa do Profile, sem misturar evidência de uma vaga de
comex com skill de tech, e vice-versa.
- Novo `Frente.java` (enum próprio, não duplicado em `Skill` e
  `ExperienciaProfissional`): valores `COMEX`, `TECH`, `TRANSVERSAL`,
  `@Enumerated(EnumType.STRING)`.
- `Skill` e `ExperienciaProfissional` ganham campo `Frente frente`.
  `TRANSVERSAL` cobre o que é transferível entre as duas (gestão de
  stakeholders, ambientes regulados, atenção a detalhe, os 15 anos de
  operações internacionais).
- Alternativa avaliada e descartada: `Set<Frente>` em vez de valor único, pra
  permitir uma skill pertencer a `{COMEX, TECH}` diretamente sem um terceiro
  valor guarda-chuva. Descartada porque, com só duas frentes reais,
  `TRANSVERSAL` cobre o mesmo caso com menos estrutura. Reabrir só se surgir
  uma terceira frente com sobreposição parcial — não é hipótese hoje.
- `bio` do `Profile` continua único, sem campo de frente. Risco baixo (não é
  `evidencia_ref` formal, a verificação da F02 seção 6.5 não o valida
  diretamente), mas fica sinalizado: frases específicas de uma frente no
  `bio` ainda entram inteiras no prompt enviado à Claude. Não resolver agora.
- Momento da decisão: nenhum dado real persistido ainda (Bloco 2 recém
  implementado, ainda não mergeado), então é a hora mais barata que existe
  pra mudar o modelo — depois disso vira migração de dado real.
- **Implementado e mergeado (2026-09-29, PR #11).** `Frente.java` criado;
  `Skill` e `ExperienciaProfissional` ganharam o campo `frente`
  (`@Enumerated(STRING)`, `nullable = false`), construtores e
  getters/setters atualizados. `ProfileService.adicionarSkill` passou a
  exigir `Frente frente` como 4º parâmetro. `mvn test` local rodado pelo
  Everson, sem problemas.
- **Duas decisões de implementação tomadas sem confirmação prévia do
  usuário** (sinalizadas aqui em vez de travar a implementação, mas a
  confirmar):
  1. `Skill.equals()`/`hashCode()` passou a incluir `frente` — duas skills
     com mesmo nome e mesmos anos, mas frente diferente, não seriam mais
     `equals()`. Na prática isso não muda comportamento visível hoje, porque
     `temNome()` (usado no dedup do Service) continua comparando só o nome.
  2. Dedup de skill (`temNome`) continua **frente-agnóstico**: o mesmo nome
     não pode existir duas vezes no Profile, mesmo em frentes diferentes.
     Testado explicitamente em
     `adicionarSkillDeveFalharQuandoNomeJaExisteMesmoEmFrenteDiferente`.
     Raciocínio: se uma skill serve às duas frentes, o valor correto é
     marcá-la `TRANSVERSAL`, não duplicar o nome com frente diferente em
     cada entrada — duplicar quebraria a garantia de "um nome, uma
     linha" que já existia antes da `Frente` existir. Faz sentido revisitar
     se aparecer um caso real de skill com peso/anos diferentes por frente.

**Skills duplicadas e inclusão de skill nova: decidido.** As duas perguntas
em aberto (bloquear duplicata? PATCH ou endpoint próprio?) se resolvem
juntas:
- `skills` ganha um endpoint de coleção próprio no Bloco 3:
  `POST /profiles/{owner}/skills` (adicionar uma skill) e
  `DELETE /profiles/{owner}/skills/{nome}` (remover). O PATCH geral do
  Profile continua só para campos escalares (`bio`).
- Motivo: o caso de uso real é "adicionar uma skill", não "reenviar o
  perfil inteiro". Com endpoint próprio, o client manda só
  `{nome, anosExperiencia}`, e o Service não precisa comparar lista contra
  lista para achar duplicata.
- Skill duplicada (mesmo `nome` já existente no Profile) é rejeitada com 409
  no `POST` — mesmo padrão de erro já usado para `owner` duplicado na F01.
  Verificação no Service, iterando `profile.getSkills()`; não depende de
  constraint no banco, porque `profile_skills` não tem PK própria para
  sustentar um UNIQUE composto sem alterar o schema.
- Nível de proficiência (iniciante/avançado etc.) como alternativa a
  `anosExperiencia` foi cogitado e descartado por ora: o valor numérico é
  mais comparável contra o texto da vaga (F02) e menos sujeito a
  autoavaliação subjetiva, que é o tipo de dado mole que a checagem de
  alucinação da F02 existe para não deixar passar sem crítica.
- Comparação de `nome` para duplicata: case-insensitive + trim (normaliza
  antes de comparar). `"Java"` e `"java"` colidem; `"Arquitetura em Java"`
  não colide com `"Java"` — é comparação de string normalizada, não busca
  por substring ou relação semântica entre skills.
- `DELETE .../skills/{nome}` de skill inexistente: 404
  (`SkillNotFoundException`), simétrico ao resto do Service.

**Implementado**: `ProfileRepository`, `ProfileService`, as 4 exceções, e
`Profile.addSkill`/`removeSkill` + `Skill.temNome` (auxiliares que faltavam
nas entidades do Bloco 1). Testes unitários do Service com Mockito em
`ProfileServiceTest` (duplicata, not-found, remoção, case-insensitive) —
não executados neste ambiente (ver nota acima).

**Lacuna conhecida — resolvida pelo Bloco 3:** `adicionarSkill` chama
`nome.trim()` sem checar `null` antes — `nome = null` estoura
`NullPointerException` cru, não um erro tratado. Isso só era um risco
real enquanto não existia validação de entrada; agora `SkillRequest.nome`
tem `@NotBlank`, e a requisição HTTP nunca chega ao Service com `nome`
nulo — o Controller responde 400 antes disso. **Continua existindo** se
o Service for chamado direto (fora do Controller, ex: em teste ou script)
com `nome` nulo — não removido, porque validação de entrada é
responsabilidade da borda HTTP, não do Service.

**Desenho do Bloco 2 (Repository/Service), antes de codar**
- `ProfileRepository extends JpaRepository<Profile, Long>`: só
  `Optional<Profile> findByOwner(String owner)` além do que o
  `JpaRepository` já dá. Como o design inteiro é chaveado por `owner`
  (IDOR aceito), quase toda operação do Service começa carregando por ele.
- `ProfileService`: `criar(owner, bio)`, `buscar(owner)`,
  `atualizar(owner, bio)` — PATCH ficou só com `bio`, já que `owner` é
  imutável e skills tem endpoint próprio —, `excluir(owner)`,
  `adicionarSkill(owner, nome, anosExperiencia, frente)`,
  `removerSkill(owner, nome)`.
- Exceções: `ProfileAlreadyExistsException` (409, `criar`),
  `ProfileNotFoundException` (404, todos os outros métodos quando o owner
  não existe), `SkillAlreadyExistsException` (409, `adicionarSkill`),
  `SkillNotFoundException` (404, `removerSkill`).
- `excluir`: carrega a entidade e chama `repository.delete(profile)` — a
  cascata do Hibernate cuida do resto (skills via `@ElementCollection`,
  histórico via `orphanRemoval`), conforme já validado.
- `historicoProfissional` fica fora do Bloco 2/3 por ora — ainda não
  decidimos o padrão de endpoint pra ele (provável espelhar skills, mas
  não fechado).

**Changelog do Bloco 1**
- 2026-09-28: constraint UNIQUE de `profiles.owner` ganhou nome fixo
  `uk_profiles_owner`, via `@UniqueConstraint` no `@Table` de `Profile` (antes
  era gerada automaticamente, ex: `ukojjnba26...`). O `unique = true` do
  `@Column` foi removido para não criar duas constraints. Confirmado contra o
  Postgres local.

**Decisões fechadas**
- Camadas simples Controller → Service → Repository.
- Unicidade de `owner`: verificação no Service + constraint UNIQUE no banco;
  409 nos dois casos. (Até 2026-10-01 o caminho do banco dava 500: o `save()`
  adiava o INSERT para o commit, fora de qualquer catch. Corrigido com
  `saveAndFlush` + catch de `DataIntegrityViolationException` no `criar`,
  mesmo padrão do `MatchService`; coberto por
  `ProfileConcorrenciaIntegrationTest`.)
- Tratamento de erro com try-catch por endpoint, sem `@ControllerAdvice`.
- PATCH com DTO de campos opcionais (`null` = não alterar).
- Skills via `@ElementCollection`; histórico via entidade própria
  `ExperienciaProfissional`.
- IDOR aceito para uso privado (reverter se a API for exposta).

## F02 — Fit Matching

Documentos: `docs/prd/F02-fit-matching.md`, `docs/specs/F02-fit-matching.md`
(**Spec concluída em 2026-09-30**). Implementação concluída e mergeada em
2026-09-30 (PRs #19 e #20, ver abaixo).

**Decisões fechadas na Spec, além do que já estava no PRD:**
- SDK oficial da Anthropic para Java (`com.anthropic:anthropic-java`), não
  HTTP cru — única exceção à regra de "sem dependência nova sem fricção
  real" porque a integração (autenticação, *tool use*) é complexidade
  recorrente, não pontual.
- `requisitos`/`gapsRiscos` como `@ElementCollection`/`@Embeddable` (mesmo
  padrão da F01), não coluna JSON — sem dependência nova.
- `evidencia_ref` usa tokens explícitos (`skill:<nome>`, `exp:<id>`) em vez
  de texto livre reescrito pela Claude — vira checagem de pertencimento a
  conjunto, não comparação fuzzy de string; evita falso positivo de
  alucinação por paráfrase.
- Saída da Claude via *tool use* (schema JSON forçado), não "responda em
  JSON" em texto livre.
- Endpoint único `POST /matches`, 201 (análise nova/reanalisada) vs 200
  (dedup, resultado existente devolvido) — diferenciação pensada
  especificamente pra fase de teste manual com crédito real.
- Falha da Claude API → 502 (erro de dependência externa), não 500.
- Causa do `revisar = true` fica só em log estruturado (SLF4J), não em
  coluna nova — gatilho de revisão: promover a coluna se precisar
  consultar por API em vez de grep.

**Implementação — passos 1-3 da Spec (seção 9), 2026-09-30.** Entidade
`MatchResult` (+ `RequisitoClassificado`, `GapRisco`, `Classificacao`,
`Decisao`) e `MatchResultRepository`, mais a lógica pura e testável sem
nenhuma dependência externa: `AderenciaCalculadora` (cálculo de
`aderenciaPct`, arredondamento pra baixo, zonas de fronteira) e
`VerificadorEvidencia` (tokens `skill:<nome>`/`exp:<id>`, Spec seção 3.1).
Pacote `com.fitanalizer.match`, mesmo estilo da F01 (getters/setters
explícitos, sem Lombok). `MatchResult.aplicarAnalise(...)` concentra num
único método toda mutação dos campos derivados de uma análise — mesmo
método serve para a primeira análise e para a reanálise (Spec, seção
6.3/7), evitando setters soltos que permitiriam atualização parcial
inconsistente.

Testes unitários cobrindo limites exatos das faixas de decisão,
arredondamento pra baixo, todas as zonas de fronteira, e os casos de
`VerificadorEvidencia` (skill válida/case-insensitive/inexistente,
experiência válida/inexistente/token malformado, referência nula/vazia,
item fora da frente já filtrado). `mvn test` local (Codespaces, 2026-09-30):
**36/36 passando** (16 novos da F02 + 20 já existentes da F01).

**Implementação — passo 4 da Spec (seção 9), 2026-09-30.**
`FitAnalysisClient` (interface) + `FitAnalysisRequest`/`FitAnalysisResult`
+ `FitAnalysisException` + `ClaudeFitAnalysisClient` (implementação real,
SDK `com.anthropic:anthropic-java`, *tool use*, prompt com tokens
`skill:<nome>`/`exp:<id>`). Properties `fitanalizer.claude.*` no
`application.yml` (`model`, `prompt-version`, `timeout-seconds`).

**Risco assumido e resolvido:** a primeira versão tinha 2 erros de
compilação (`tools(List<Tool>)` → precisa `ToolUnion.ofTool(...)`;
`toolUse.input()` → accessor correto é `toolUse._input()`), reportados
pelo Everson (`mvn compile` no Codespaces) e corrigidos consultando o
código-fonte real do SDK no GitHub. **`mvn compile` confirmado: BUILD
SUCCESS (2026-09-30).** Nenhum teste automatizado para esta classe ainda,
de propósito — só faz sentido depois de ter a chave de API pra validar
com uma chamada real. (Resolvido em 2026-10-01: ver "Primeira chamada
real" abaixo.)

**Implementação — passos 5-6 da Spec (seção 9), 2026-09-30.**
`MatchService` (filtro por frente, dedup com `saveAndFlush` +
`DataIntegrityViolationException` pra concorrência, verificação de
evidência, cálculo, marca `revisar` com causas logadas) + `MatchController`
(`POST /matches`, 201/200/400/404/502) + DTOs (`MatchRequest`,
`MatchResponse`, `RequisitoResponse`, `GapRiscoResponse`).

**Detalhe técnico que vale registrar:** o dedup usa `saveAndFlush`, não
`save`. Com `save()` simples o Hibernate adia a escrita (write-behind) até
o fim da transação — a violação da UNIQUE só apareceria depois do método
já ter retornado, e o `try/catch` de concorrência nunca pegaria nada.
`saveAndFlush` força o INSERT/UPDATE real ali, dentro do `try`.

Testes unitários (`MatchServiceTest`, Mockito): frente TRANSVERSAL rejeitada
sem buscar Profile, owner sem Profile sem chamar Claude, dedup com/sem
reanalisar, filtro por frente confirmado via `ArgumentCaptor` (skill/
experiência de outra frente não vai no prompt), vaga sem requisitos
(inconclusiva), evidência inventada (rebaixamento + revisar), frente
divergente (revisar sem afetar o cálculo). Web-slice (`MatchControllerTest`):
201, 200 (dedup), 400 (validação e frente inválida), 404, 502.

**Validado (2026-09-30):** `mvn test` local (Codespaces) passou e a PR #19
foi mergeada. **Todos os passos da ordem sugerida na Spec (seção 9) estão
implementados.** A chamada real à Claude API foi feita em 2026-10-01 (ver
"Primeira chamada real" abaixo).

**Primeira chamada real à Claude API (2026-10-01, PR #23).** Perfil
fictício `teste` (3 skills TECH, 1 experiência COMEX) e uma vaga curta com 4
requisitos obrigatórios (Java, Spring Boot, PostgreSQL, Kafka). Resultado:
**HTTP 201, `aderenciaPct` 75, `cv_carta`, `revisar=false`**, Kafka como
lacuna; repetindo a mesma chamada, **HTTP 200** com o mesmo `id` e o mesmo
`analisadoEm`, sem nova chamada à API (dedup confirmado de ponta a ponta,
com o Postgres e a API reais). O prompt e o *tool use* funcionaram sem
ajuste; o custo é da ordem de meio centavo de dólar por análise (Haiku 4.5).

**Dois bugs empilhados, invisíveis sem chamar a API de verdade.** A primeira
tentativa deu 502 "fora do schema esperado", embora a Claude tivesse
respondido certo. (1) O `ClaudeFitAnalysisClient` lia a entrada da ferramenta
via `toString()` + Jackson, mas o `toString()` de um `JsonObject` do SDK
imprime formato de Map do Java (`{chave=valor}`), que não é JSON. (2) Depois
de corrigir isso, o Jackson interno do SDK (`JsonValue.convert`) não
constrói *records* ("no Creators exist"). **Correção:** o SDK converte só
para um `Map` simples e o `ObjectMapper` do projeto (com
`FAIL_ON_UNKNOWN_PROPERTIES` desligado) monta o record. A causa real de uma
falha de conversão passou a ser **logada** (antes só a mensagem genérica
chegava ao Controller, e o 502 não dava pista nenhuma). Teste unitário novo
(`ClaudeFitAnalysisClientTest`) cobre a conversão sem chamar a API.
Lição: o segundo bug só apareceu depois de corrigir o primeiro; integração
com serviço externo nunca exercitada tende a esconder mais de um problema.

**Chave da Claude API (2026-10-01).** Chave dedicada ao projeto, criada no
Console; guardada como **secret de Codespaces do próprio repositório**
(Settings → Secrets and variables → Codespaces), nunca em arquivo do repo.
O secret de usuário com o mesmo nome já existia e serve aos repositórios do
curso da Alura (não inclui o Fit-Analizer), então foi deixado intacto. O SDK
lê `ANTHROPIC_API_KEY` do ambiente.

**Teste de integração do dedup e correção de bug de concorrência
(2026-09-30, PR #20).** O Mockito não consegue provar comportamento de
banco, então o dedup ganhou `MatchDedupIntegrationTest`, contra um Postgres
real via Testcontainers (nunca H2: o que se quer provar é a UNIQUE e o
momento do flush no Postgres de verdade). Três casos: mesma vaga duas vezes
(devolve o salvo, Claude chamada uma vez), UNIQUE recusando duplicata direto
no repository, e duas requisições simultâneas da mesma vaga (1 linha, sem
exceção). Os testes de concorrência não podem ser `@Transactional`: precisam
de commits reais, com limpeza manual no `@AfterEach`.

**Bug achado por esse teste:** o `catch (DataIntegrityViolationException)`
do `MatchService` rodava **dentro da mesma transação que falhou**. No
Postgres, depois de uma violação de constraint a transação aborta e a sessão
do Hibernate fica inutilizável (`AssertionFailure: null id ... don't flush
the Session after an exception occurs` ao reconsultar). Duas requisições
simultâneas da mesma vaga davam erro em vez de devolver o resultado salvo.
Os testes unitários com Mockito não podiam mostrar isso.

**Correção:** `MatchService.analisar` deixou de ser `@Transactional` e roda
em três etapas, via `TransactionTemplate`: (1) transação curta de leitura +
checagem de dedup; (2) chamada à Claude e cálculos, **sem transação e sem
conexão de banco presa** (antes, a chamada de até 60s segurava uma conexão);
(3) transação curta só para gravar. Se a gravação violar a UNIQUE, só essa
transação é descartada, e a leitura de recuperação roda numa transação nova.
Sem reanálise, a etapa 3 sempre faz INSERT (quem arbitra a corrida é a
UNIQUE, não um "já existe?" que ficaria velho); na reanálise, sobrescreve a
mesma linha. **Consequência aceita:** numa corrida real, a Claude pode ser
chamada duas vezes pela mesma vaga — a consistência (1 linha, sem erro) é
garantida, custo zero não. A corrida só ocorre com requisições simultâneas
da mesma vaga; o caso comum (coletor reenviando em execuções sucessivas) é
coberto pela checagem prévia.

**Recorte:** analisa uma vaga contra o Profile de um owner e persiste o
`MatchResult`. Ranking e consulta ficam na F03.

**Entrada:** endpoint único para uso manual e coletor, mesmo contrato:
texto da vaga, `owner`, `frente` (obrigatório: `COMEX` ou `TECH`), `vagaUrl`
opcional, `reanalisar=true` opcional.

**Divisão LLM/código**
- A Claude só classifica os requisitos obrigatórios (forte/parcial/nenhum),
  com `evidencia_ref` apontando o item do Profile que sustenta a evidência.
- O Java calcula `aderenciaPct` (pesos 1.0/0.5/0, arredondando para baixo) e
  aplica as faixas: 85–100 `cv_prioritario`, 70–84 `cv_carta`,
  50–69 `cv_carta_com_aviso`, 30–49 `nao_candidatar`, 0–29 `fora_escopo`.

**Provedor de LLM: decidido (2026-09-30).** Claude API, modelo **Haiku 4.5**
(não Sonnet). Contexto: cogitou-se trocar para Groq (modelos abertos,
infra própria, tokens mais baratos) por preocupação de custo, já que o
plano Pro não inclui créditos de API. Descartado depois de comparar preço
real por análise, não só por MTok:
- Uma análise de fit (~2.500 tokens de entrada — Profile + vaga — e ~500 de
  saída) custa ~$0,005 no Haiku 4.5 ($1/$5 por MTok). Um crédito de €5
  cobre mais de 1.000 análises — muito acima do volume real de uma busca de
  emprego pessoal (dezenas a poucas centenas de vagas). O medo de custo
  fazia sentido pensando em Sonnet (~4-5x mais caro), não em Haiku.
- A vantagem central do Groq é velocidade de inferência, que não entrega
  nada aqui: a F02 é um processo assíncrono de backend (coletor ou uso
  manual chamando um endpoint), não uma UI de chat esperando tokens em
  tempo real.
- O risco nomeado no próprio protocolo de análise (nunca inventar evidência
  que não está no Profile) é uma questão de fidelidade/instrução, dimensão
  em que os modelos da Anthropic têm histórico mais forte do que os modelos
  abertos hospedados no Groq — não validado com benchmark próprio, é
  julgamento, mas é o motivo de não trocar sem necessidade comprovada.
- Reforça a decisão abaixo (segunda camada de LLM como revisor fora do
  escopo do v1): com uma só chamada por análise, e não duas, o custo real
  fica ainda mais baixo do que a conta inicial.

**Frente no contrato de entrada: decidido (2026-09-29).** `frente` é
parâmetro **obrigatório** (`COMEX` ou `TECH`), não inferência livre da
Claude. Motivo: tanto o coletor quanto o uso manual já sabem a frente da
vaga no momento da chamada (o coletor busca por frente; no uso manual, é
quem está a candidatar-se). Deixar a Claude classificar sozinha reabriria,
no nível da frente, o mesmo problema que a verificação de evidência (seção
6.5 do PRD) existe pra evitar no nível do requisito: confiar sem checagem
num julgamento de LLM. A Claude também classifica a frente a partir do
texto como parte da resposta — se divergir do parâmetro informado, vira
`revisar = true` (reaproveita o mecanismo das zonas de fronteira, em vez de
criar um caminho de erro novo). `TRANSVERSAL` não é valor de entrada válido
para `frente` — é atributo de skill/experiência, não classificação possível
de uma vaga. Detalhe completo na seção 6.4a do PRD.

**Entidade `MatchResult`:** `frente` (`COMEX`/`TECH`, herdada do parâmetro de
entrada), `vaga_chave` NOT NULL (URL, ou `hash:` + SHA-256 do texto
normalizado), `vagaUrl` em coluna própria, UNIQUE
`(profile_id, vaga_chave)`, campos `requisitos`, `gapsRiscos`, `decisao`,
`revisar`, versão do prompt, modelo e `analisadoEm`.

**Regras**
- Verificação determinística contra alucinação: requisito cuja evidência não
  existe no Profile, **dentro do subconjunto filtrado pela `frente` da vaga
  (+ `TRANSVERSAL`)**, é rebaixado para `nenhum` e logado. Skill/experiência
  da outra frente não conta como evidência, mesmo que exista no Profile.
- `revisar = true` nas zonas de fronteira (28–32, 48–52, 68–72, 83–87), quando
  houve rebaixamento, **ou quando a classificação de frente que a Claude faz
  a partir do texto diverge do parâmetro `frente` informado na chamada.**
- Vaga sem requisitos obrigatórios persiste com 0%, `fora_escopo`,
  `revisar = true`.
- Endpoint síncrono, timeout de ~60s; em falha não persiste nada e não há
  retry automático (`maxRetries(0)` explícito no cliente do SDK, cujo padrão
  é 2 — até 2026-10-01 o SDK fazia até 2 retries sem a gente saber).
- **Política de retry:** sem retry no cliente. Quando o coletor existir, o
  retry com backoff é responsabilidade dele. Erros transitórios da Claude API
  (429, 529, outros 5xx, timeout) chegam ao cliente HTTP como 502 com
  `ErrorResponse`. O dedup do `MatchService` (UNIQUE `profile_id` +
  `vaga_chave`) torna a repetição segura: depois que uma vaga foi analisada e
  salva, reenviá-la não gera nova cobrança. Duas exceções conhecidas: numa
  corrida (duas requisições simultâneas da mesma vaga) a Claude pode ser
  chamada duas vezes (aceito, ver acima); e um timeout do nosso lado não
  garante que a API não processou e cobrou aquela tentativa.
- `textoVaga` acima de `fitanalizer.match.vaga-max-chars` (padrão 15000,
  env `VAGA_MAX_CHARS`) → 400, sem chamar a Claude API. Checado no
  Controller, não com `@Size`, porque anotação não lê propriedade.
- Concorrência: a segunda requisição devolve o resultado já salvo. Diverge da
  F01 (409) porque o coletor precisa de idempotência.
- Excluir um Profile apaga os `MatchResult` (delete explícito no Service da
  F02 — ver decisão abaixo, não cascata no banco).

**Pendências de segurança (fora do PR de endurecimento, 2026-10-01)**
- Autenticação com rate limit no `/matches`: hoje qualquer um que alcance a
  API pode disparar análises e gastar crédito da Claude.
- Delimitar o texto da vaga no prompt como dado, nunca como instrução
  (proteção contra prompt injection vinda de vagas coletadas).

**Decisões a confirmar na Spec**
- **Mecanismo da cascata Profile → `MatchResult`: decidido (delete explícito
  no Service da F02).** A cascata do Hibernate só funciona a partir de um
  mapeamento no lado pai (`@OneToMany` com `cascade` em `Profile`, como a F01
  faz com `historicoProfissional`). Com apenas `@ManyToOne` em `MatchResult`
  e sem lista em `Profile`, esse padrão não se transfere:
  `repository.delete(profile)` não apagaria os `MatchResult` e falharia na
  FK. Opções avaliadas:
  (a) `@OnDelete(action = CASCADE)` no `@ManyToOne` de `MatchResult` — gera
  `ON DELETE CASCADE` no banco; garantia mais forte (funciona por qualquer
  caminho de delete), mas quebra a convenção do projeto de cascata só via
  Hibernate, e o comportamento só aparece no schema, não na classe Java;
  (b) `@OneToMany(cascade, orphanRemoval)` de `MatchResult` dentro de
  `Profile` — mantém o padrão do Hibernate, mas acopla a entidade `Profile`
  ao conceito de `MatchResult`, revertendo a regra de isolamento da F01;
  (c) **[escolhida]** o Service da F02 apaga os `MatchResult` do owner antes
  de deletar o Profile. Sem mudança de schema e sem acoplar a entidade
  `Profile` — ela continua sem saber que `MatchResult` existe. O custo é
  disciplina: só funciona se todo caminho que deleta um Profile passar por
  esse Service; um endpoint admin ou script futuro que apague Profile direto
  deixaria `MatchResult` órfãos. Anotar isso como responsabilidade do Service
  na Spec da F02, e revisitar se surgir outro caminho de delete de Profile.

**Fora do escopo do v1:** segunda camada de LLM como revisor (gatilho: mais de
3 discordâncias em 15 vagas de teste, ou `revisar` acima de ~30% — reavaliado
em 2026-09-29 e mantido fora de escopo conscientemente: ainda não há dado de
uso real pra saber se algum gatilho dispara; adiado, não esquecido;
**reconfirmado em 2026-09-30** ao decidir o provedor de LLM — `revisar = true`
continua sendo sinalização pra revisão humana, não gatilho de nova chamada),
F03, guardar o texto da vaga, **geração de CV adaptado (vira F04, ver
abaixo)**.

## F04 — Geração de CV

**Decidido (2026-09-29): feature separada, não entra na F02.** Sem PRD
ainda. Consome o `MatchResult` da F02 (incluindo `frente`) como insumo.

Motivo de ficar fora da F02: matching é julgamento estruturado e verificável
(fórmula de aderência, dedup, checagem de alucinação). Geração de CV é
trabalho generativo, sujeito a iteração — o usuário vai querer olhar, ajustar,
pedir outra versão. Misturar as duas naturezas numa feature só reabriria um
PRD da F02 já fechado pra acomodar um tipo de trabalho diferente.

Como o texto da vaga **não é persistido** no `MatchResult` (decisão já
fechada como fora de escopo do v1 da F02), a F04 decide isso na própria
PRD — provavelmente pedindo o texto de novo no momento de gerar o CV
(natural: só se pede CV depois de ver o resultado do match, e nesse ponto a
vaga está sendo olhada de novo mesmo). Não reabre a decisão da F02.

A regra de nunca misturar stack técnica num CV de comex (e vice-versa) se
resolve de graça: a F04 filtra o Profile pela `frente` do `MatchResult` que
está consumindo, do mesmo jeito que a F02 filtra pra verificação de
evidência (seção 6.4a do PRD).

Gatilho pra escrever o PRD: quando a F02 estiver implementada e em uso real,
com `MatchResult`s de decisão `cv_prioritario`/`cv_carta` acumulados.

## F03 — Ranking

Sem PRD. Vai listar `MatchResult` por decisão e aderência.

**Atenção:** `aderenciaPct` usa 0, e não nulo, para análises inconclusivas.
Manter assim para não ordenar errado no PostgreSQL (nulos vão para o topo em
`ORDER BY ... DESC`).

## Coletor de vagas (projeto externo)

Sem PRD. Stateless, sem LLM, linguagem em aberto. Lê o Profile para saber o
que buscar, consulta APIs públicas de emprego e envia cada vaga para a F02.

**Fontes levantadas** (só pela documentação, ainda não testadas):
- Começar com: ITJobs.pt (exige chave grátis por e-mail), Jobicy, Remotive.
- Avaliar depois: Himalayas, freehire.me, Arbeitnow, RemoteOK.
- **BEP — Bolsa de Emprego Público** (`https://www.bep.gov.pt/Default.aspx`):
  vagas da Administração Pública portuguesa. **Verificar antes de implementar:**
  não encontrei API pública documentada, só formulário de busca em HTML — o
  que quebraria o padrão "todas com API pública real, sem scraping" das
  outras fontes. Existiu um projeto de dados abertos de terceiros
  (`empregopublico.github.io`) que fazia esse scraping, mas está
  descontinuado. Confirmar se há webservice/API oficial antes de decidir
  entrar como fonte.

## Ambiente

JDK 21 instalado e alinhado com o `pom.xml` na máquina principal.

**GitHub Codespaces vem com JDK 25 por padrão** — incompatível em runtime
com o ByteBuddy embutido no Mockito do Spring Boot 3.3.4 (suporta bytecode
só até Java 23). Só afeta testes que usam `@MockBean` do Spring
(`ProfileControllerTest`, primeiro no projeto a usar isso — o
`@Mock` simples do Mockito em `ProfileServiceTest` não é afetado, usa
caminho mais leve). Corrigido com
`-Dnet.bytebuddy.experimental=true` no `maven-surefire-plugin` (pom.xml) —
não é downgrade de JDK nem mudança de bytecode alvo, só permite o
ByteBuddy tentar instrumentar mesmo numa JVM mais nova do que ele
oficialmente testou. Remover essa flag quando uma versão futura do Spring
Boot trouxer ByteBuddy com suporte nativo a Java 25.

**Testes de integração e Docker (2026-09-30).** `MatchDedupIntegrationTest`
precisa de Docker rodando. O Testcontainers do BOM do Spring Boot 3.3.4
(1.19.8) usa uma API do Docker antiga que o **Docker Engine 29+ recusa**
("client version 1.32 is too old"); o `pom.xml` sobrescreve
`testcontainers.version` para **2.0.5**, que negocia a versão da API sozinha.
Na 2.x os módulos foram renomeados (`testcontainers-junit-jupiter`,
`testcontainers-postgresql`) e `PostgreSQLContainer` mudou de pacote.

**CI (2026-09-30, PR #21).** `.github/workflows/ci.yml`: GitHub Actions roda
`./mvnw test` (Java 21, Temurin, cache do Maven) em todo PR e em todo push
para `main`, sem secrets (os testes mockam o `FitAnalysisClient`). O runner
`ubuntu-latest` já tem Docker, então o Testcontainers funciona lá. Só há CI;
não há CD porque a aplicação ainda não está hospedada em lugar nenhum.
**Pendente (manual, só o dono do repo):** regra em Settings → Rules exigindo o
check `test` antes de mergear em `main`; sem ela o CI avisa mas não bloqueia.

## Próximos passos (nesta ordem)

1. ~~Validar o schema da F01 contra PostgreSQL real.~~ Feito.
2. ~~Bloco 2 (Repository/Service) e decisões de skill duplicada/remoção.~~
   Mergeado, `mvn test` local rodado (6/6, via Codespaces).
3. ~~Adicionar `Frente.java` e o campo `frente` em `Skill` e
   `ExperienciaProfissional`.~~ Mergeado (PR #11), `mvn test` local ok.
4. ~~F01 — Bloco 3 (Controller/DTOs), já contemplando `frente` e
   experiências.~~ Mergeado (PR #12), corrigido nos PRs #13 (ByteBuddy/JDK
   25) e #14 (comentário XML inválido no pom.xml), `mvn test` local:
   20/20 passando.
5. ~~Priorizar CI (GitHub Actions rodando `mvn test` em todo PR).~~ Feito
   (PR #21, 2026-09-30); os PRs #13 e #14 só existiram porque não havia
   rede automática pegando esses erros antes do merge. **Falta a regra em
   Settings → Rules exigindo o check `test`** (ver seção "Ambiente").
6. ~~Spec da F02, já contemplando o parâmetro `frente`, a checagem cruzada e
   o provedor de LLM decidido (Claude Haiku 4.5).~~ Concluída (2026-09-30),
   `docs/specs/F02-fit-matching.md`. **Implementação concluída e mergeada**
   (PRs #19 e #20, 2026-09-30). **Chamada real à Claude API validada**
   (2026-10-01, PR #23): 201 e 200 de dedup, ver seção F02.
7. PRD do coletor e da F03.
8. PRD da F04 (Geração de CV) — só depois da F02 implementada e em uso real.
