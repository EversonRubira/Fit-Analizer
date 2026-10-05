# PRD — F02: Fit Matching

**Projeto:** Fit Analizer
**Status:** Rascunho para implementação
**Data:** 2026-09-28
**PRD relacionado:** `docs/prd/F01-cadastro-perfil-tecnico.md` (fornece o Profile consumido aqui)

## 1. Sumário executivo

A F02 analisa **uma** vaga de emprego contra o Profile técnico de um `owner`
(cadastrado pela F01) e persiste o resultado como um `MatchResult`. A análise
é dividida entre dois responsáveis com papéis bem separados:

- **Claude API** faz apenas o julgamento: extrai os requisitos obrigatórios da
  vaga e classifica a cobertura de cada um pelo Profile, apontando a evidência.
- **Código Java** faz todo o resto: verifica se a evidência citada existe de
  fato no Profile, calcula a aderência percentual, define a decisão por faixas
  e marca resultados que merecem revisão humana.

A feature expõe um **único endpoint**, usado tanto para vagas coladas à mão
quanto para vagas enviadas por um coletor externo (projeto separado). Como o
coletor reenvia as mesmas vagas a cada execução, a F02 deduplica por
`owner + vaga` antes de chamar a Claude API, evitando custo repetido.

Consultar e ranquear resultados é responsabilidade da F03, fora deste PRD.

## 2. Problema

Com o Profile estruturado da F01 disponível, falta a parte que dá sentido ao
sistema: dizer, para uma vaga concreta, **o quanto o perfil cobre o que a vaga
exige** e **o que fazer a respeito** (priorizar, candidatar com carta, não
candidatar). Fazer isso manualmente para cada vaga é lento e inconsistente.

Delegar a análise inteira a um LLM cria outros problemas:
- números calculados por LLM não são reprodutíveis nem auditáveis;
- o LLM pode inventar evidência ("tem experiência com Kafka") que não existe
  no perfil, inflando o resultado;
- um coletor automático reenviaria a mesma vaga a cada execução, multiplicando
  o custo de chamadas à API sem gerar informação nova.

## 3. Oportunidade

- **Julgamento no LLM, regra no código:** o LLM faz o que só ele faz bem
  (interpretar texto livre de vaga e relacionar com o perfil); o código faz o
  que precisa ser determinístico (conta, faixas, verificação). O número final
  é explicável e testável com JUnit.
- **Verificação barata de alucinação:** conferir se a evidência citada existe
  no Profile não custa nenhuma chamada extra de LLM e elimina a principal fonte
  de resultado inflado.
- **Um endpoint para dois usos:** o mesmo contrato atende o uso manual e o
  coletor, sem duplicar lógica.
- **Dedup antes da chamada:** o custo da Claude API passa a ser proporcional a
  vagas *novas*, não a execuções do coletor.

## 4. Audiência

- **Quem usa:**
  - os dois usuários do sistema (mesmos da F01), colando o texto de uma vaga
    manualmente para análise pontual;
  - o **coletor externo** (projeto separado, stateless, sem banco próprio), que
    busca vagas em APIs públicas de emprego e as envia ao mesmo endpoint.
- **Necessidade:** saber rapidamente se vale a pena se candidatar a uma vaga,
  com qual esforço (CV apenas, CV + carta, CV + carta com aviso) e quais gaps
  ou riscos existem.
- **Experiência esperada:** uma chamada HTTP com o texto da vaga devolve o
  resultado completo (aderência, decisão, requisitos classificados, gaps e
  riscos, marca de revisão). Reenviar a mesma vaga devolve o resultado salvo,
  instantaneamente e sem custo. Sem interface própria em v1, como na F01.
- **Nível técnico:** usuários técnicos, confortáveis com API REST.

## 5. Objetivos e métricas

| Objetivo | Métrica |
|---|---|
| A análise concorda com o julgamento humano | Conjunto de regressão de **10 a 15 vagas reais julgadas à mão** pelo usuário; a análise discorda do usuário em **no máximo 3 de 15** vagas. Executado **sempre que o prompt mudar**. |
| Nenhuma evidência inventada chega ao cálculo | 100% das `evidencia_ref` que não existem no Profile são rebaixadas para `nenhum` e registradas no log como alucinação |
| Custo proporcional a vagas novas | 0 chamadas à Claude API para `owner + vaga_chave` já analisados (exceto com `reanalisar=true`) |
| Resultado reprodutível a partir da resposta do LLM | Dada a mesma lista de requisitos classificados, `aderenciaPct` e `decisao` são sempre os mesmos (cálculo 100% em código, coberto por testes unitários) |
| Casos duvidosos ficam visíveis | Todo resultado em zona de fronteira, com rebaixamento por alucinação ou inconclusivo é persistido com `revisar = true` |

**Definição de discordância:** no conjunto de regressão, "discordar" significa
a `decisao` produzida ser diferente da decisão que o usuário atribuiu à vaga
(não diferença pontual de percentual). Para cada vaga, o conjunto registra
também a **distância em faixas** entre a decisão da Claude e a do usuário
(ex: `cv_carta` vs. `cv_carta_com_aviso` = 1 faixa; `cv_prioritario` vs.
`nao_candidatar` = 3 faixas), para diferenciar erro de fronteira de erro
grosseiro.

**Proporção de `revisar`:** o gatilho da fase 2 (ver seção 9) começa em
**30%** dos resultados com `revisar = true`, valor de partida a calibrar com o
uso real.

## 6. Features

### F02 — Fit Matching

**O que faz:** recebe o texto bruto de uma vaga, o `owner` e, opcionalmente, a
URL da vaga; analisa a vaga contra o Profile do `owner` via Claude API; aplica
verificação e cálculo em código; persiste e devolve um `MatchResult`.

#### 6.1 Contrato de entrada (um único endpoint)

| Campo | Obrigatório | Descrição |
|---|---|---|
| `owner` | sim | Identificador do dono do Profile (mesmo da F01) |
| `textoVaga` | sim | Texto bruto da vaga |
| `frente` | sim | `COMEX` ou `TECH` — a frente de carreira que a vaga representa (ver seção 6.4a) |
| `vagaUrl` | não | URL da vaga. Sempre enviada pelo coletor; opcional no uso manual |
| `reanalisar` | não | Parâmetro opcional. Quando `true`, ignora o dedup e sobrescreve o resultado existente. O coletor **nunca** envia |

O uso manual e o coletor chamam **o mesmo endpoint** com **o mesmo contrato**.
Não existe endpoint separado para o coletor.

**Assumption:** `frente` é obrigatório e informado por quem chama — não é
inferência livre da Claude. Tanto o coletor quanto o uso manual já sabem a
frente da vaga no momento da chamada (o coletor busca por frente; no uso
manual, é a pessoa que está a candidatar-se que sabe). Deixar a Claude
classificar sozinha, sem esse parâmetro, reabriria no nível da frente o
mesmo problema que a verificação da seção 6.5 existe para evitar no nível da
evidência: confiar sem checagem num julgamento de LLM. Ver seção 6.4a.

**Assumption:** `frente` reutiliza o enum `Frente` da F01
(`COMEX`, `TECH`, `TRANSVERSAL`), mas só `COMEX` e `TECH` são valores válidos
de entrada aqui — `TRANSVERSAL` é uma propriedade de skill/experiência
(o que é transferível entre as duas frentes), não uma classificação possível
para a vaga em si. Uma vaga é sempre de uma frente ou da outra.

**Assumption:** o formato exato (body JSON vs. query param para `reanalisar`,
path, códigos HTTP) fica para a Spec em `docs/specs/`.

#### 6.2 Chave da vaga e dedup

- `vaga_chave` é **sempre preenchida (NOT NULL)**:
  - `vagaUrl`, quando informada;
  - caso contrário, `"hash:"` + SHA-256 do texto normalizado.
- **Normalização antes do hash:** minúsculas, trim, e colapso de espaços e
  quebras de linha em um único espaço.
- **Defesa em profundidade (mesmo padrão da F01):**
  1. **Service:** antes de chamar a Claude API, busca `MatchResult` por
     `profile + vaga_chave`. Se existir e `reanalisar` não for `true`, devolve
     o resultado salvo **sem chamar a Claude API**.
  2. **Banco:** constraint `UNIQUE (profile_id, vaga_chave)`. Como a coluna é
     NOT NULL, não há o furo de `NULL` não conflitar com `NULL` no PostgreSQL.
- **Concorrência:** se duas requisições da mesma vaga passarem pela verificação
  do Service e a segunda falhar na constraint UNIQUE, a segunda **devolve o
  resultado já salvo**, e não erro.
  - **Divergência consciente da F01:** a F01 devolve 409 nesse cenário. A F02
    não, porque o coletor precisa de **idempotência**: reenviar a mesma vaga
    deve sempre resultar no mesmo `MatchResult`, nunca em erro. Custo aceito:
    uma chamada à Claude API desperdiçada nesse caso raro.

**Custos aceitos conscientemente:**
- Uma colagem manual com diferença real de conteúdo gera outro hash e é
  reanalisada (custo: uma chamada extra, sem risco de dado errado).
- A mesma vaga enviada pelo coletor (chave = URL) e colada à mão sem URL
  (chave = hash) conta como **duas** análises — não há como ligá-las sem a URL.

**Assumption:** a URL é usada como chave **exatamente como chega**, sem
normalização (ex: parâmetros de rastreio como `?utm=` geram chaves diferentes).
Aceitável porque o coletor reenvia a URL da API de origem sempre igual.

#### 6.3 Reanálise (`reanalisar=true`)

- Ignora o dedup, chama a Claude API e **sobrescreve a mesma linha** do
  `MatchResult` (a UNIQUE continua valendo).
- Na sobrescrita, também são atualizados: versão do prompt, modelo usado e
  `analisadoEm`.
- Uso exclusivamente manual; o coletor nunca envia.

**Risco aceito:** um resultado salvo fica **desatualizado** depois de uma
edição no Profile (F01) até o usuário pedir a reanálise. Não há invalidação
automática nem endpoint de exclusão em v1.

**Gatilho de revisão:** se reanalisar vagas uma a uma virar trabalho
repetitivo, ou se resultados desatualizados forem parar em decisões reais de
candidatura, avaliar invalidação por marcador de versão do Profile ou
reanálise em lote.

**Assumption:** `reanalisar=true` para uma vaga sem resultado prévio analisa e
cria normalmente, sem erro.

#### 6.4 Divisão de trabalho: Claude API × código

**Claude API (somente julgamento), resposta sempre em JSON estruturado, nunca
texto livre:**
- `requisitos`: lista de **todos** os requisitos **obrigatórios** da vaga,
  **inclusive os sem cobertura** (o código precisa do denominador). Cada item:
  - descrição do requisito;
  - classificação: `forte`, `parcial` ou `nenhum`;
  - `evidencia_ref`: qual skill ou experiência do Profile sustenta a
    classificação (quando houver).
- `gaps_riscos`: lista de `{ gap, risco }`.

Requisitos desejáveis **não** são pedidos à Claude nem exibidos em v1.

**Código Java (todo o resto):**
1. **Verificação de evidência** (seção 6.5).
2. **Cálculo da aderência:**
   `aderenciaPct = (soma dos pesos dos obrigatórios / total de obrigatórios) × 100`,
   com `forte = 1.0`, `parcial = 0.5`, `nenhum = 0`, **arredondando sempre
   para baixo** (resultado inteiro). O cálculo usa as classificações **já
   verificadas** (após rebaixamentos).
3. **Decisão por faixas:**

   | aderenciaPct | decisao |
   |---|---|
   | 85–100 | `cv_prioritario` |
   | 70–84 | `cv_carta` |
   | 50–69 | `cv_carta_com_aviso` |
   | 30–49 | `nao_candidatar` |
   | 0–29 | `fora_escopo` |

4. **Marca de revisão** (seção 6.6).

#### 6.4a Frente da vaga

O Profile da F01 passa a ter `Frente` (`COMEX`/`TECH`/`TRANSVERSAL`) em cada
skill e experiência profissional. A F02 usa o parâmetro `frente` do
contrato de entrada (seção 6.1) em dois pontos:

1. **Escopo do que é enviado à Claude:** o insumo do Profile enviado no
   prompt (seção 8) é filtrado para conter apenas skills/experiências da
   `frente` informada **+ `TRANSVERSAL`**. Não envia dado da outra frente —
   evita que evidência de uma vaga de comex apareça, mesmo por engano, numa
   análise de tech, e vice-versa.
2. **Checagem cruzada:** a Claude também classifica a frente da vaga a
   partir do próprio texto, como parte da resposta estruturada. Se a
   classificação da Claude divergir do parâmetro `frente` informado, o
   resultado é marcado `revisar = true` (condição nova na seção 6.6) — mesmo
   mecanismo já usado para zonas de fronteira de aderência, reaproveitado em
   vez de criar um caminho de erro novo.

**Assumption:** a checagem cruzada gera `revisar = true`, não erro nem
rejeição da análise — o parâmetro informado por quem chama continua sendo a
fonte de verdade usada no filtro do Profile; a divergência só sinaliza que
vale conferência humana.

#### 6.5 Verificação de evidência (v1, sem custo de LLM)

Regra: **a Claude nunca pode inventar evidência.**

- Após a resposta, o código confere se cada `evidencia_ref` existe de fato no
  Profile **daquele owner**, dentro do subconjunto já filtrado pela `frente`
  (seção 6.4a) — skill ou experiência de outra frente não conta como
  evidência válida, mesmo que exista no Profile.
- Se não existir: o requisito é **rebaixado para `nenhum`**, o evento é
  registrado no log como **alucinação**, e a análise **segue** (não rejeita a
  análise inteira).

**Assumption:** requisito classificado como `forte` ou `parcial` **sem**
`evidencia_ref` recebe o mesmo tratamento de alucinação (rebaixado para
`nenhum` e logado). Requisito `nenhum` não precisa de referência.

**Assumption:** a regra exata de correspondência entre `evidencia_ref` e o
Profile (ex: nome de skill com ou sem distinção de maiúsculas, como
referenciar uma experiência) é definida na Spec.

**Assumption:** a referência inválida vai apenas para o log; o requisito
persiste como `nenhum`, sem a referência rejeitada.

#### 6.6 Marca `revisar`

`revisar = true` quando **qualquer** das condições abaixo ocorrer:
- `aderenciaPct` em zona de fronteira de decisão: **28–32, 48–52, 68–72,
  83–87**;
- algum requisito foi **rebaixado** por evidência inexistente no Profile;
- a análise é **inconclusiva** (seção 6.7);
- a classificação de frente da Claude diverge do parâmetro `frente`
  informado na chamada (seção 6.4a).

O log registra a **causa** de cada `revisar = true` (fronteira, evidência
rebaixada ou inconclusiva; mais de uma quando coincidirem), para que a
proporção de revisões possa ser analisada por motivo ao calibrar o gatilho
da fase 2.

#### 6.7 Vaga sem requisitos obrigatórios / texto que não é vaga

Quando a Claude não identifica nenhum requisito obrigatório (vaga genérica
demais) ou o texto enviado não parece ser uma vaga:
- persiste com `aderenciaPct = 0`, `decisao = fora_escopo`, `revisar = true`;
- `gaps_riscos` recebe a entrada fixa:
  `{ gap: "sem requisitos obrigatórios identificáveis", risco: "análise inconclusiva, o número não vem de um cálculo real" }`;
- o evento é registrado no log.

Motivo: persistir faz o dedup proteger contra reprocessamento pelo coletor;
se o prompt melhorar depois, `reanalisar=true` corrige a vaga.

**Assumption:** a Claude sinaliza "não é uma vaga" devolvendo a lista de
requisitos obrigatórios vazia, e o código trata os dois casos pelo mesmo
caminho.

**Gatilho de revisão:** se, na F03, resultados inconclusivos forem confundidos
com 0% legítimos, criar um valor de decisão próprio (`inconclusiva`) e deixar
`aderenciaPct` nulo.

#### 6.8 Entidade `MatchResult`

| Campo | Descrição |
|---|---|
| `id` | Identificador |
| `profile` | `@ManyToOne` para o `Profile` da F01 |
| `frente` | `COMEX` ou `TECH` — a frente informada na chamada (seção 6.4a). Persistida para a F03 ranquear por frente e para a F04 filtrar o Profile ao gerar CV |
| `vagaChave` | NOT NULL. URL ou `"hash:" + SHA-256`. Parte da UNIQUE `(profile_id, vaga_chave)` |
| `vagaUrl` | URL da vaga em coluna própria, **nula quando não informada**. Para exibição na F03 |
| `aderenciaPct` | Inteiro 0–100, calculado em código |
| `requisitos` | Todos os obrigatórios classificados (`forte`/`parcial`/`nenhum`), com `evidencia_ref` quando houver |
| `gapsRiscos` | Lista de `{ gap, risco }` |
| `decisao` | Uma das 5 decisões da seção 6.4 |
| `revisar` | Marca de revisão humana (seção 6.6) |
| `versaoPrompt` | Versão do prompt usada na análise vigente |
| `modelo` | Modelo da Claude usado na análise vigente |
| `analisadoEm` | Data/hora da análise vigente (atualizada na reanálise) |

O texto da vaga **não é persistido** — apenas o hash, quando usado como chave.

**Assumption:** o campo de data se chama `analisadoEm` (e não `createdAt`)
porque é atualizado na reanálise; o nome precisa refletir "quando a análise
vigente foi feita", não "quando a linha foi criada".

**Assumption:** a forma de armazenar `requisitos` e `gapsRiscos` (tabela
filha, `@ElementCollection` ou coluna JSON) é decisão da Spec.

#### 6.9 Tratamento de erro

| Situação | Comportamento |
|---|---|
| `owner`, `textoVaga` ausentes ou vazios | Erro de requisição inválida; nada é chamado nem persistido |
| `owner` sem Profile cadastrado | Erro de não encontrado; **não chama** a Claude API |
| Resultado já existe (sem `reanalisar`) | Devolve o resultado salvo; não é erro |
| Violação da UNIQUE por concorrência | Devolve o resultado já salvo; não é erro (seção 6.2) |
| Evidência inexistente no Profile | Não é erro: rebaixa, loga, `revisar = true` |
| Nenhum requisito obrigatório / texto não é vaga | Não é erro: resultado inconclusivo persistido (seção 6.7) |

**Assumption:** falha da Claude API (erro HTTP, timeout ou JSON inválido/fora
do schema) devolve erro ao cliente, **nada é persistido** e **não há retry
automático** em v1. O coletor tenta de novo na próxima execução.

**Assumption:** códigos HTTP exatos e formato do corpo de erro ficam para a
Spec.

#### 6.10 Arquitetura e segurança (herdadas da F01)

- Camadas simples **Controller → Service → Repository**, como na F01.
- O cliente da Claude API fica atrás de uma interface (`FitAnalysisClient`);
  detalhes na Spec.
- **Chave da Claude API somente por variável de ambiente**, nunca no código
  nem no repositório.
- **Risco aceito de IDOR:** sem autenticação, quem souber o `owner` de outra
  pessoa consegue disparar análises (e gastar chamadas) contra o Profile dela.
  Mesma condição de reversão da F01: se a API for exposta fora de ambiente
  privado, autenticação real passa a ser pré-requisito.

#### 6.11 Critérios de aceitação

- Uma vaga nova é analisada, persistida e devolvida com todos os campos da
  seção 6.8.
- Reenviar a mesma vaga (mesma URL, ou mesmo texto normalizado sem URL) para o
  mesmo owner devolve o resultado salvo **sem chamar** a Claude API.
- `reanalisar=true` chama a Claude API e sobrescreve a mesma linha, atualizando
  versão do prompt, modelo e `analisadoEm`.
- `aderenciaPct` e `decisao` batem com a fórmula e as faixas para qualquer
  combinação de classificações (testes unitários, incluindo arredondamento
  para baixo e limites de faixa).
- `evidencia_ref` inexistente no Profile rebaixa o requisito para `nenhum`,
  gera log de alucinação e marca `revisar = true`.
- Resultados em 28–32, 48–52, 68–72, 83–87 são persistidos com
  `revisar = true`.
- Vaga sem obrigatórios persiste 0% / `fora_escopo` / `revisar = true` com a
  entrada fixa em `gaps_riscos`.
- Owner sem Profile não gera chamada à Claude API.
- O conjunto de regressão de 10–15 vagas existe e é executado quando o prompt
  muda, com no máximo 3 discordâncias em 15.

## 7. User stories

- Como usuário, quero colar o texto de uma vaga (com ou sem URL) e receber a
  aderência, a decisão, os requisitos classificados e os gaps/riscos, para
  decidir rápido se e como me candidatar.
- Como usuário, quero que a análise nunca conte como coberta uma skill ou
  experiência que eu não tenho no meu Profile, para não me candidatar com base
  em um número inflado.
- Como usuário, quero que resultados perto de uma fronteira de decisão, com
  evidência rebaixada ou inconclusivos venham marcados para revisão, para
  saber onde meu julgamento ainda é necessário.
- Como usuário, quero pedir a reanálise de uma vaga depois de atualizar meu
  Profile ou depois de uma melhoria no prompt, sem criar resultado duplicado.
- Como coletor, quero enviar vagas ao mesmo endpoint do uso manual e receber
  sempre o mesmo resultado para uma vaga já analisada, sem erro e sem custo
  novo, para poder rodar repetidamente sem guardar estado.
- Como mantenedor, quero um conjunto de vagas julgadas à mão que sirva de
  teste de regressão sempre que o prompt mudar, para detectar piora de
  qualidade antes de ela afetar decisões reais.

## 8. Consumes/Provides

### F02 — Fit Matching

**Consumes:**
- **F01 — Cadastro de Perfil Técnico:** o `Profile` do `owner` (skills com
  anos de experiência, histórico profissional com tecnologias usadas, bio),
  incluindo a `Frente` (`COMEX`/`TECH`/`TRANSVERSAL`) de cada skill e
  experiência. Usado em dois momentos:
  1. como insumo enviado à Claude API para a classificação, já filtrado pela
     `frente` da vaga + `TRANSVERSAL` (seção 6.4a);
  2. como fonte de verdade na verificação de `evidencia_ref`, dentro do
     mesmo subconjunto filtrado.
- **Identificador `owner`** definido pela F01, reutilizado como chave de acesso.
- **Claude API** (externa), via `FitAnalysisClient`, com chave por variável de
  ambiente.
- **Coletor externo** (fora deste PRD): origem de vagas automáticas, como
  cliente HTTP do endpoint.

**Provides:**
- **Para a F03 (consulta e ranking):** `MatchResult` persistido por
  `profile`, com `frente`, `aderenciaPct`, `decisao`, `revisar`,
  `requisitos`, `gapsRiscos`, `vagaUrl` (para exibição), `versaoPrompt`,
  `modelo` e `analisadoEm`. Garantia: no máximo **um** resultado por
  `(profile, vaga_chave)`.
- **Para a F04 (geração de CV, fora deste PRD):** o `MatchResult`, incluindo
  `frente`, como insumo para filtrar o Profile ao gerar o CV adaptado. A F04
  decide, na própria PRD, se precisa do texto da vaga (não persistido aqui —
  ver seção 9) reenviado no momento da geração.
- **Para o coletor:** o `MatchResult` como resposta HTTP do mesmo endpoint,
  idempotente para a mesma vaga.

**Nota de integração com a F01:** excluir um Profile na F01 **apaga junto os
`MatchResult` associados** — são dado derivado e recalculável. A F01 **não
passa a conhecer** o `MatchResult`: a dependência continua em um único
sentido (F02 → F01). **Decidido** (não fica mais para a Spec): a cascata é
feita no nível de aplicação — o Service da F02 apaga os `MatchResult` do
owner antes de deletar o Profile — e não como `ON DELETE CASCADE` no banco.
Motivo e trade-offs registrados no `STATUS.md`.

```mermaid
graph LR
    F01["F01 — Cadastro de Perfil Técnico"]
    F02["F02 — Fit Matching"]
    F03["F03 — Consulta e Ranking (futuro)"]
    COL["Coletor externo (projeto separado)"]
    MAN["Uso manual (cliente HTTP)"]
    CLAUDE["Claude API"]

    F01 -->|"Profile por owner"| F02
    MAN -->|"textoVaga + owner + frente + vagaUrl? + reanalisar?"| F02
    COL -->|"textoVaga + owner + frente + vagaUrl"| F02
    F02 -->|"Profile + vaga → requisitos classificados (JSON)"| CLAUDE
    F02 -->|"MatchResult (resposta HTTP)"| COL
    F02 -->|"MatchResult persistido"| F03
```

## 9. Fora de escopo

- **Segunda camada de LLM (revisor)** que valida ou refina a primeira análise,
  de preferência com outro modelo e rubrica explícita. Registrada como
  **fase 2**, com gatilho:
  - a Claude discordar do usuário em **mais de 3 de 15** vagas do conjunto de
    teste julgado à mão; **ou**
  - a proporção de resultados com `revisar = true` passar de **30%** (ponto de
    partida, a calibrar; ver seção 5).
  - Reavaliado em 2026-09-29 e mantido fora de escopo, conscientemente: ainda
    não há dado de uso real para saber se algum dos gatilhos dispara.
    Adiado, não esquecido.
- **Geração de CV adaptado à vaga.** Vira **F04 — Geração de CV**, com PRD
  próprio, consumindo o `MatchResult` desta feature (incluindo `frente`).
  Motivo de não entrar aqui: matching é julgamento estruturado e verificável
  (fórmula de aderência, dedup, checagem de alucinação); geração de CV é
  trabalho generativo, sujeito a iteração — natureza diferente o bastante
  para não misturar num PRD já fechado. Detalhe registrado no `STATUS.md`.
- **Consulta e ranking de resultados** — F03.
- **O coletor** — projeto separado; aqui aparece apenas como cliente HTTP.
- **Persistir o texto da vaga** no `MatchResult` — apenas o hash, quando usado
  como chave.
- **Requisitos desejáveis** — não pedidos à Claude nem exibidos em v1.
- **Invalidação automática** de resultados quando o Profile muda.
- **Endpoint de exclusão** de `MatchResult`.
- **Reanálise em lote.**
- **Normalização de URL** (remoção de parâmetros de rastreio etc.).
- **Retry automático** de chamadas à Claude API.
- **Endpoint separado para o coletor.**
- **Autenticação/autorização real** — mesmo risco aceito da F01.

## 10. Decisões de 2026-10-02

**Origem das vagas.**
- A origem das vagas é **externa** ao Fit Analizer e pode ser manual (colar
  o texto) ou um agente de IA com busca na web. O contrato do `/matches` não
  muda: texto bruto da vaga + `owner` (com `frente`, `vagaUrl?` e
  `reanalisar?`, como na seção 6.1).
- O coletor como código próprio **deixa de ser planeado**. Só será
  construído se colar vagas à mão virar fricção real.
- O Fit Analizer continua sendo o **único** sistema com Profile, Claude API e
  lógica de julgamento.

**Escopo de uso.**
- Uso pessoal, rodando só na máquina do dono. **Não** será hospedado nem
  exposto na internet.
- Por isso ficam fora de escopo: autenticação por chave de API, rate limit,
  hospedagem em nuvem e migração urgente para Flyway. **Gatilho de
  reabertura:** se o escopo mudar (hospedar, mostrar a terceiros), essas
  decisões devem ser reabertas, e o `ddl-auto: update` revisto junto.
- Cuidado mantido: limite mensal de gasto no console da Anthropic.
- **Pendente:** configurar `server.address=127.0.0.1` (ainda não está no
  `application.yml`).
- **Descartado: RAG sobre o Profile.** O Profile cabe inteiro no prompt, e
  RAG acrescentaria complexidade sem ganho. Reavaliar apenas se o Profile
  crescer muito.

**Processo de qualidade.**
- Conjunto de regressão do prompt (`RegressaoPromptTest`, tag `regressao`,
  5 casos reais com decisão esperada escrita à mão **antes** de rodar).
  Regra: mudou o prompt ou o formato de saída, roda a regressão contra a API
  real. Cada erro novo da Claude em vaga real vira um caso novo. As fixtures
  versionadas contêm **apenas exemplos**; as versões reais ficam locais e
  **nunca devem ser commitadas**. Pendência futura: mover as fixtures reais
  para uma pasta no `.gitignore`, com o teste caindo para os exemplos quando
  ela não existir.
- Dedup considera a versão do prompt: análise salva com versão diferente (ou
  sem versão) é reprocessada, sobrescrevendo a mesma linha. Subir
  `prompt-version` **só quando o prompt mudar de verdade**, porque cada vaga
  reenviada é cobrada de novo uma vez.
- Lição do bug dos colchetes (PR #27): o cliente falso dos testes devolvia
  tokens limpos e escondeu um bug real (aderência 0% em produção). Mudanças
  no prompt ou no formato de saída da Claude precisam de **pelo menos uma
  chamada real**.

**Em aberto (hipótese, não decisão).**
- Primeira rodada completa da regressão (2026-10-02, modelo
  `claude-haiku-4-5`):

  | caso | esperado | obtido | aderência |
  |---|---|---|---|
  | vaga-01 | `cv_carta` | `nao_candidatar` | 31% |
  | vaga-02 | — | bateu | — |
  | vaga-03 | — | bateu | — |
  | vaga-04 | `nao_candidatar` | `cv_carta_com_aviso` | 63% |
  | vaga-05 | `nao_candidatar` | `cv_carta` | 70% |

- **Hipótese:** o prompt não pesa senioridade ("Senior") nem anos mínimos de
  experiência (ex: "3+ anos de Java" contra 1 ano no Profile), e é duro
  demais com requisitos subjetivos de atitude (vaga-01).
- **Proposta a validar:** tratar senioridade e anos mínimos como filtro em
  código, fora do LLM, comparando com os anos do Profile. Só entra neste PRD
  como decisão depois de o diagnóstico das vagas 01 e 05 confirmar a causa e
  a regressão validar a regra.
- **Próximo passo:** diagnóstico com `-Dregressao.casos=vaga-01` e depois
  `vaga-05`.

## 11. Decisões de 2026-10-05

Resolve a hipótese em aberto da seção 10 para a vaga-05 (PR #29).

**Causa raiz (vaga-05: 70%, `cv_carta`; esperado `nao_candidatar`).**
- **Denominador inflado:** listas da vaga viravam vários requisitos. "SQL,
  Postgres, MySQL, MongoDB" virou vários itens FORTE e "Inglês e Português"
  virou 2 — 12 requisitos no total, 8 deles FORTE.
- **Eliminatórios pesando igual aos demais:** "3+ anos de Java" com 1 ano no
  Profile ficou PARCIAL e pesou o mesmo que "Git"; nada limitava a decisão.

**Regra.**
- ~~Uma linha da vaga = um requisito; listas não são divididas.~~ Substituída
  no prompt v4 (ver abaixo).
- A Claude só extrai `tecnologia` e `anosMinimos`; a comparação com
  `anosExperiencia` da skill do Profile é feita em código
  (`VerificadorAnosMinimos`). Abaixo do mínimo ou skill ausente → NENHUM,
  com `revisar=true`.
- **Teto `nao_candidatar` só para requisitos com `anosMinimos`** que terminam
  NENHUM (`Decisao.aplicarTeto`, regra única). O teto nunca melhora a decisão.
- **Formação, nível de idioma e disponibilidade/localização não são
  eliminatórios:** quando ficam NENHUM, só ligam `revisar` (e entram em
  `gapsRiscos`), sem limitar a decisão.
- **Campo de formação no Profile: descartado de propósito.** Só será modelado
  se a falta dele virar fricção real.

**Custo.** `prompt-version` v2 → v3: pela regra de dedup da seção 10, cada
vaga reenviada é reanalisada (e cobrada) uma vez.

**Limitação conhecida.** Senioridade sem número de anos ("Senior") fica
marcada `eliminatorio`, mas **não** aciona o teto: não há dado no Profile
para comparar. Uma vaga "Senior" sem anos explícitos pode continuar acima de
`nao_candidatar`.

**Prompt v4 (mesmo PR #29).** A rodada de regressão com o v3
(`claude-haiku-4-5`) mostrou três problemas:
1. **`evidenciaRef` em lista** ("skill:Docker, exp:1"): o verificador tratava a
   string inteira como uma referência e rebaixava para NENHUM.
2. **`anosMinimos` copiado do Profile** em requisitos sem mínimo nenhum
   ("Domínio de Python" com `anosMinimos=2`).
3. **"Uma linha, um requisito" escondia lacunas:** "Java8+, Springboot, Kafka,
   etc" ficou PARCIAL só pelo Java; "APIs, microserviços, Git e CI/CD" ficou
   FORTE só com REST APIs.

O v4 corrige:
- **Evidência:** o prompt pede um token por requisito (o mais forte). O
  `VerificadorEvidencia` aceita lista separada por vírgula como rede de
  segurança: mantém a classificação só se **todos** os tokens existirem no
  Profile, grava a lista normalizada e loga os tokens inválidos. Fora isso,
  continua estrito.
- **Anos mínimos:** o prompt só preenche `anosMinimos` com número explícito
  na vaga, nunca a partir do Profile. Em código, `VerificadorAnosMinimos` e o
  teto (`Decisao.aplicarTeto`) só consideram requisito com `eliminatorio=true`.
  **O teto por `anosMinimos` continua**, agora restrito a eliminatórios.
- **Listas:** cada tecnologia ou competência distinta vira um requisito
  próprio, sem duplicar o mesmo requisito em dois itens.

**Risco a vigiar na regressão.** Separar listas é o oposto da regra do v3,
criada contra o denominador inflado da vaga-05 ("SQL, Postgres, MySQL,
MongoDB" virando vários FORTE). O teto por eliminatório é a proteção que
existe hoje para esse caso; a vaga-05 precisa ser conferida na próxima rodada.
Alternativas ("RabbitMQ ou Kafka") não estão tratadas no prompt.

**Custo.** `prompt-version` v3 → v4: cada vaga reenviada é reanalisada (e
cobrada) uma vez.

**Pendente.** Rodar a regressão contra a API real com o prompt v4 (não rodada
no PR #29). A vaga-01 (requisitos de atitude) continua sem diagnóstico.
