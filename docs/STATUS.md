# Status do projeto — Fit Analizer

**Atualizado em:** 2026-09-28

## Visão geral

| Item | PRD | Spec | Implementação |
|---|---|---|---|
| F01 — Cadastro de Perfil Técnico | Concluído | Concluída | Blocos 0 e 1 na main; Blocos 2 e 3 não iniciados |
| F02 — Fit Matching | Concluído | Não iniciada | Não iniciada |
| F03 — Ranking | Não iniciado | — | — |
| Coletor de vagas (projeto externo) | Não iniciado | — | — |

## F01 — Cadastro de Perfil Técnico

Documentos: `docs/prd/F01-cadastro-perfil-tecnico.md`, `docs/specs/F01-cadastro-perfil-tecnico.md`.

**Andamento**
- Bloco 0 (setup) e Bloco 1 (entidades JPA): concluídos e mergeados na main.
- Bloco 2 (Repository/Service): não iniciado.
- Bloco 3 (Controller/DTOs): não iniciado.

**Pendência antes do Bloco 2:** validar o schema gerado pelo Hibernate contra
um PostgreSQL real.

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

1. Validar o schema da F01 contra PostgreSQL real (em casa).
2. F01 — Bloco 2 (Repository/Service).
3. F01 — Bloco 3 (Controller/DTOs).
4. Spec da F02.
5. PRD do coletor e da F03.
