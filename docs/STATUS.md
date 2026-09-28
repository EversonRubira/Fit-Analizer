# Status do projeto — Fit Analizer

**Atualizado em:** 2026-09-28

## Visão geral

| Item | PRD | Spec | Implementação |
|---|---|---|---|
| F01 — Cadastro de Perfil Técnico | Concluído | Concluída | Blocos 0 e 1 na main, schema validado; Blocos 2 e 3 não iniciados |
| F02 — Fit Matching | Concluído | Não iniciada | Não iniciada |
| F03 — Ranking | Não iniciado | — | — |
| Coletor de vagas (projeto externo) | Não iniciado | — | — |

## F01 — Cadastro de Perfil Técnico

Documentos: `docs/prd/F01-cadastro-perfil-tecnico.md`, `docs/specs/F01-cadastro-perfil-tecnico.md`.

**Andamento**
- Bloco 0 (setup) e Bloco 1 (entidades JPA): concluídos e mergeados na main.
- Bloco 2 (Repository/Service): não iniciado.
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

**Pergunta em aberto (calibrar no início do Bloco 2):** skills duplicadas
(mesmo `nome` no mesmo perfil) devem ser bloqueadas? Se sim, em qual camada,
Service ou validação de DTO? Hoje o schema permite duplicata, porque
`profile_skills` não tem PK nem UNIQUE.

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
texto da vaga, `owner`, `vagaUrl` opcional, `reanalisar=true` opcional.

**Divisão LLM/código**
- A Claude só classifica os requisitos obrigatórios (forte/parcial/nenhum),
  com `evidencia_ref` apontando o item do Profile que sustenta a evidência.
- O Java calcula `aderenciaPct` (pesos 1.0/0.5/0, arredondando para baixo) e
  aplica as faixas: 85–100 `cv_prioritario`, 70–84 `cv_carta`,
  50–69 `cv_carta_com_aviso`, 30–49 `nao_candidatar`, 0–29 `fora_escopo`.

**Entidade `MatchResult`:** `vaga_chave` NOT NULL (URL, ou `hash:` + SHA-256
do texto normalizado), `vagaUrl` em coluna própria, UNIQUE
`(profile_id, vaga_chave)`, campos `requisitos`, `gapsRiscos`, `decisao`,
`revisar`, versão do prompt, modelo e `analisadoEm`.

**Regras**
- Verificação determinística contra alucinação: requisito cuja evidência não
  existe no Profile é rebaixado para `nenhum` e logado.
- `revisar = true` nas zonas de fronteira (28–32, 48–52, 68–72, 83–87) ou
  quando houve rebaixamento.
- Vaga sem requisitos obrigatórios persiste com 0%, `fora_escopo`,
  `revisar = true`.
- Endpoint síncrono, timeout de ~60s; em falha não persiste nada e não há
  retry automático.
- Concorrência: a segunda requisição devolve o resultado já salvo. Diverge da
  F01 (409) porque o coletor precisa de idempotência.
- Excluir um Profile apaga os `MatchResult` (cascata declarada do lado da F02).

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
3 discordâncias em 15 vagas de teste, ou `revisar` acima de ~30%), F03, guardar
o texto da vaga.

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

## Ambiente

JDK 21 instalado e alinhado com o `pom.xml`.

## Próximos passos (nesta ordem)

1. ~~Validar o schema da F01 contra PostgreSQL real.~~ Feito.
2. F01 — Bloco 2 (Repository/Service), começando pela pergunta das skills
   duplicadas.
3. F01 — Bloco 3 (Controller/DTOs).
4. Spec da F02.
5. PRD do coletor e da F03.
