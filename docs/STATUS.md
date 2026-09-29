# Status do projeto — Fit Analizer

**Atualizado em:** 2026-09-29

## Visão geral

| Item | PRD | Spec | Implementação |
|---|---|---|---|
| F01 — Cadastro de Perfil Técnico | Concluído | Concluída | Blocos 0 e 1 na main, schema validado; Bloco 2 implementado (não testado localmente); Bloco 3 não iniciado |
| F02 — Fit Matching | Concluído | Não iniciada | Não iniciada |
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
- Bloco 3 (Controller/DTOs): não iniciado.

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
- Ainda não implementado: essa mudança de entidade entra junto com o resto
  do Bloco 2/3, não foi codada nesta sessão (sessão foi só de decisão).

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

**Lacuna conhecida:** `adicionarSkill` chama `nome.trim()` sem checar
`null` antes — `nome = null` estoura `NullPointerException` cru, não um
erro tratado. Deixado assim de propósito: validação de entrada
(`@NotBlank` etc.) é responsabilidade do DTO no Bloco 3, que ainda não
existe. Enquanto isso, chamar o Service direto (fora de um Controller com
DTO validado) com `nome` nulo quebra sem mensagem clara.

**Desenho do Bloco 2 (Repository/Service), antes de codar**
- `ProfileRepository extends JpaRepository<Profile, Long>`: só
  `Optional<Profile> findByOwner(String owner)` além do que o
  `JpaRepository` já dá. Como o design inteiro é chaveado por `owner`
  (IDOR aceito), quase toda operação do Service começa carregando por ele.
- `ProfileService`: `criar(owner, bio)`, `buscar(owner)`,
  `atualizar(owner, bio)` — PATCH ficou só com `bio`, já que `owner` é
  imutável e skills tem endpoint próprio —, `excluir(owner)`,
  `adicionarSkill(owner, nome, anosExperiencia)`,
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
  409 nos dois casos.
- Tratamento de erro com try-catch por endpoint, sem `@ControllerAdvice`.
- PATCH com DTO de campos opcionais (`null` = não alterar).
- Skills via `@ElementCollection`; histórico via entidade própria
  `ExperienciaProfissional`.
- IDOR aceito para uso privado (reverter se a API for exposta).

## F02 — Fit Matching

Documento: `docs/prd/F02-fit-matching.md`. Spec e implementação não iniciadas;
dependem da F01 completa.

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
  retry automático.
- Concorrência: a segunda requisição devolve o resultado já salvo. Diverge da
  F01 (409) porque o coletor precisa de idempotência.
- Excluir um Profile apaga os `MatchResult` (delete explícito no Service da
  F02 — ver decisão abaixo, não cascata no banco).

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
uso real pra saber se algum gatilho dispara; adiado, não esquecido), F03,
guardar o texto da vaga, **geração de CV adaptado (vira F04, ver abaixo)**.

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

JDK 21 instalado e alinhado com o `pom.xml`.

## Próximos passos (nesta ordem)

1. ~~Validar o schema da F01 contra PostgreSQL real.~~ Feito.
2. ~~Bloco 2 (Repository/Service) e decisões de skill duplicada/remoção.~~
   PR aberto, aguardando `mvn test` local e merge.
3. Adicionar `Frente.java` e o campo `frente` em `Skill` e
   `ExperienciaProfissional` — pendente desde a decisão de 2026-09-29, entra
   junto do Bloco 2/3 antes de qualquer dado real ser persistido.
4. F01 — Bloco 3 (Controller/DTOs), já contemplando `frente`.
5. Spec da F02, já contemplando o parâmetro `frente` e a checagem cruzada.
6. PRD do coletor e da F03.
7. PRD da F04 (Geração de CV) — só depois da F02 implementada e em uso real.
