# Spec — F02: Fit Matching

**PRD de referência:** `docs/prd/F02-fit-matching.md`
**Status:** Rascunho para implementação
**Data:** 2026-09-30

Esta Spec fecha as `Assumption` deixadas em aberto no PRD (formato HTTP,
armazenamento de `requisitos`/`gapsRiscos`, regra de correspondência de
`evidencia_ref`, integração com a Claude API) e decide o provedor de LLM
(ver `STATUS.md`, seção F02: Claude API, modelo **Haiku 4.5**).

## 1. Arquitetura

### 1.1 Camadas

Mesmo estilo da F01: **Controller → Service → Repository**, sem
Hexagonal/Clean Architecture. A única peça nova é o **cliente da Claude
API**, isolado atrás de uma interface própria — não porque exista mais de
um provedor hoje (decidiu-se manter só Claude, ver `STATUS.md`), mas porque
é uma dependência externa real (rede, custo, formato de resposta) que não
deve vazar para o Service como código HTTP solto.

```java
public interface FitAnalysisClient {
    FitAnalysisResult analisar(FitAnalysisRequest request);
}
```

`FitAnalysisRequest{textoVaga, List<Skill> skills, List<ExperienciaProfissional> experiencias}`
— já recebe o Profile **filtrado pela frente** (seção 4). `FitAnalysisResult{Frente frenteDetectada, List<RequisitoClassificado> requisitos, List<GapRisco> gapsRiscos}`.

**Decisão: usar o SDK oficial da Anthropic para Java** (`com.anthropic:anthropic-java`),
não montar a chamada HTTP à mão. Diferente da decisão de não usar MapStruct
na F01 (3 DTOs simples não justificavam uma dependência), aqui a
integração tem complexidade real e recorrente: formato de mensagem,
autenticação por header, e principalmente **tool use** (seção 3.2), que o
SDK tipa nativamente. Escrever isso à mão significaria reimplementar um
cliente HTTP com parsing de JSON aninhado — exatamente o tipo de trabalho
que uma dependência mantida pela própria Anthropic existe para evitar.

### 1.2 Configuração

- `ANTHROPIC_API_KEY` — variável de ambiente, nunca no código ou
  `application.yml` (mesma regra da F01 para segredos).
- `fitanalizer.claude.model` — property (`application.yml`, default
  `claude-haiku-4-5`), não constante fixa no código. Motivo: trocar de
  modelo (ex: para recalibrar custo/qualidade) não deve exigir recompilar.
- `fitanalizer.claude.prompt-version` — string versionando o **conteúdo**
  do prompt (não o SDK nem o modelo), persistida em `MatchResult.versaoPrompt`
  (PRD, seção 6.8). Incrementada manualmente a cada mudança de prompt —
  é o que aciona o conjunto de regressão de 10–15 vagas (PRD, seção 5).

## 2. Entidade `MatchResult`

```java
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"profile_id", "vaga_chave"}))
public class MatchResult {
    @Id @GeneratedValue Long id;
    @ManyToOne(fetch = LAZY) Profile profile;
    @Enumerated(STRING) Frente frente;
    @Column(name = "vaga_chave", nullable = false) String vagaChave;
    @Column(name = "vaga_url") String vagaUrl;
    Integer aderenciaPct;
    @ElementCollection(fetch = LAZY)
    @CollectionTable(name = "match_result_requisitos", joinColumns = @JoinColumn(name = "match_result_id"))
    @OrderColumn(name = "ordem")
    List<RequisitoClassificado> requisitos;
    @ElementCollection(fetch = LAZY)
    @CollectionTable(name = "match_result_gaps_riscos", joinColumns = @JoinColumn(name = "match_result_id"))
    @OrderColumn(name = "ordem")
    List<GapRisco> gapsRiscos;
    @Enumerated(STRING) Decisao decisao;
    boolean revisar;
    String versaoPrompt;
    String modelo;
    Instant analisadoEm;
}

@Embeddable
public class RequisitoClassificado {
    String descricao;
    @Enumerated(STRING) Classificacao classificacao; // FORTE, PARCIAL, NENHUM
    String evidenciaRef; // nullable — formato na seção 3.1
}

@Embeddable
public class GapRisco {
    String gap;
    String risco;
}
```

**Por que `@ElementCollection`/`@Embeddable`, não coluna JSON:** o projeto
já usa esse padrão (`Profile.skills`, `tecnologiasUsadas`) e ele é
suficiente — `requisitos` e `gapsRiscos` são listas de objetos pequenos e
fixos, sem necessidade de schema flexível. Uma coluna `jsonb` exigiria uma
dependência nova (ex: `hypersistence-utils`) para mapear bem no Hibernate,
sem resolver nenhuma fricção que o `@ElementCollection` já não resolva.
Custo aceito: adicionar um campo a `RequisitoClassificado` no futuro exige
migração de schema, não só mudar a leitura do JSON — aceitável, é o mesmo
trade-off já aceito nas outras coleções da F01.

**Repetição consciente do achado da F01 (Spec, seção 2.3):** `requisitos` e
`gapsRiscos` são `LAZY`. Como o Controller monta o DTO de resposta fora da
transação, o Service precisa `Hibernate.initialize(...)` as duas antes de
retornar — mesmo padrão de `buscarOuFalhar`, aplicado aqui a
`buscarMatchResultOuFalhar`.

**`vagaChave`:** calculada em código antes de qualquer chamada à Claude —
`vagaUrl` quando informada, senão `"hash:" + sha256(normalizar(textoVaga))`.
Normalização: `toLowerCase().trim()` seguido de
`replaceAll("\\s+", " ")`. `MessageDigest.getInstance("SHA-256")`, sem
biblioteca externa.

## 3. Contrato do prompt e verificação de evidência

### 3.1 Referência de evidência — tokens explícitos, não texto livre

Problema a evitar: se a Claude tiver que **reescrever** o nome de uma
skill como evidência, qualquer diferença de acentuação, maiúscula ou
paráfrase faria o código rebaixar uma evidência real como "inventada" —
um falso positivo de alucinação, não um erro real.

**Decisão:** o Profile filtrado (seção 4) é enviado ao prompt com um
**token de referência explícito** por item, e a Claude é instruída a
sempre citar esse token exato em `evidencia_ref`, nunca reescrever o nome:

- Skill: `skill:<nome>` — `nome` já é garantidamente único no Profile
  (dedup da F01, seção "Skills duplicadas" do `STATUS.md`), então serve
  como identificador estável sem precisar de um `id` próprio (`Skill` é
  `@ElementCollection`, sem PK).
- Experiência: `exp:<id>` — `ExperienciaProfissional` já tem `id` gerado
  (entidade própria desde a F01 Bloco 3).

Isso transforma a verificação de "alucinação" (PRD, seção 6.5) numa
**checagem de pertencimento a um conjunto conhecido**, não numa comparação
de texto:

```java
boolean evidenciaValida(String evidenciaRef, List<Skill> skills, List<ExperienciaProfissional> experiencias) {
    if (evidenciaRef.startsWith("skill:")) {
        String nome = evidenciaRef.substring(6);
        return skills.stream().anyMatch(s -> s.temNome(nome)); // reaproveita normalização já testada na F01
    }
    if (evidenciaRef.startsWith("exp:")) {
        Long id = Long.valueOf(evidenciaRef.substring(4));
        return experiencias.stream().anyMatch(e -> e.getId().equals(id));
    }
    return false; // token fora do formato esperado também conta como inválido
}
```

`skills`/`experiencias` aqui já são o subconjunto **filtrado pela frente**
(seção 4) — o mesmo usado no prompt. Uma referência a algo que existe no
Profile mas fora da frente da vaga é tratada como inválida, exatamente
como o PRD exige (seção 6.4a).

Requisito `forte`/`parcial` sem `evidencia_ref`, ou com token fora do
formato (`evidenciaValida` retorna `false`), é rebaixado para `NENHUM` e
logado como alucinação — regra única, sem caso especial (PRD, seção 6.5,
`Assumption` confirmada).

### 3.2 Saída estruturada via *tool use*

**Decisão:** a resposta da Claude é obtida forçando uma chamada de
ferramenta (*tool use*) com o schema abaixo, em vez de pedir "responda em
JSON" em texto livre. Motivo: *tool use* garante que a resposta já vem no
formato esperado (o SDK valida contra o schema antes de devolver), eliminando
a classe de erro "JSON quase certo, mas com vírgula sobrando ou campo
faltando" que pediria um parser tolerante a erro. Reforça também a decisão
de manter só a Claude API (STATUS.md): esse tipo de garantia de schema não
é padrão em todo provedor.

```json
{
  "name": "classificar_fit",
  "input_schema": {
    "type": "object",
    "properties": {
      "frenteDetectada": {"type": "string", "enum": ["COMEX", "TECH"]},
      "requisitos": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "descricao": {"type": "string"},
            "classificacao": {"type": "string", "enum": ["forte", "parcial", "nenhum"]},
            "evidenciaRef": {"type": ["string", "null"]}
          },
          "required": ["descricao", "classificacao"]
        }
      },
      "gapsRiscos": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {"gap": {"type": "string"}, "risco": {"type": "string"}},
          "required": ["gap", "risco"]
        }
      }
    },
    "required": ["frenteDetectada", "requisitos", "gapsRiscos"]
  }
}
```

Lista de `requisitos` vazia → caminho da seção 6.7 do PRD (vaga sem
obrigatórios / texto que não é vaga).

### 3.3 Falha da Claude API

Timeout de 60s (configurado no cliente do SDK). Qualquer falha — timeout,
erro HTTP, ou a Claude não chamar a ferramenta — propaga uma
`FitAnalysisException` (`RuntimeException`), capturada no Controller como
**502 Bad Gateway** (não 500: o erro é de uma dependência externa, não do
próprio serviço — distinção útil pro coletor decidir se vale reintentar).
Nada é persistido (PRD, seção 6.9, `Assumption` confirmada). Sem retry
automático em v1.

## 4. Filtro de Profile por frente

Antes de montar `FitAnalysisRequest`, o Service filtra:

```java
List<Skill> skillsRelevantes = profile.getSkills().stream()
        .filter(s -> s.getFrente() == frente || s.getFrente() == Frente.TRANSVERSAL)
        .toList();
```

Mesmo padrão para `historicoProfissional`. Esse subconjunto é usado nos
dois pontos que o PRD exige (seção 6.4a): montagem do prompt **e**
verificação de `evidencia_ref` (seção 3.1) — um único método
`profileFiltradoPorFrente(profile, frente)` no Service, para não duplicar
o filtro em dois lugares que poderiam divergir.

## 5. Endpoint HTTP

| Método | Path | Corpo | Sucesso | Erros |
|---|---|---|---|---|
| POST | `/matches` | `MatchRequest{owner, textoVaga, frente, vagaUrl?, reanalisar?}` | 201 (análise nova ou reanalisada) / 200 (resultado já existente devolvido) | 400, 404, 502 |

Só um verbo, um path — reflete o "endpoint único" do PRD (seção 6.1).
`201` vs `200` diferencia semanticamente se uma análise **nova** foi
produzida ou se foi só uma devolução do que já existia — informação
pequena, mas correta para quem estiver testando a dedup na prática (é
exatamente o teste que Everson vai rodar com os €5 de crédito).

**`MatchRequest`:**
```java
public record MatchRequest(
        @NotBlank String owner,
        @NotBlank String textoVaga,
        @NotNull Frente frente,
        String vagaUrl,
        Boolean reanalisar) {
}
```

`frente` aceita os 3 valores do enum na deserialização, mas
`TRANSVERSAL` não é entrada válida (PRD, seção 6.1). Verificação no
Service, não em Bean Validation (`@NotNull` não distingue valores dentro
do enum) — lança `FrenteInvalidaException` → **400**, mesmo padrão de
try-catch por endpoint já usado na F01.

**`MatchResponse`** (`de(MatchResult)`): `id`, `frente`, `vagaUrl`,
`aderenciaPct`, `requisitos` (lista de `{descricao, classificacao,
evidenciaRef}`), `gapsRiscos`, `decisao`, `revisar`, `versaoPrompt`,
`modelo`, `analisadoEm`. `versaoPrompt`/`modelo` **entram** na resposta —
diferente do reflexo inicial de tratar como "campo interno" — porque
Everson vai estar comparando resultados manualmente durante o teste com
crédito real, e saber qual prompt/modelo gerou qual resultado é
justamente o dado que essa fase de validação precisa. `vagaChave` **não**
entra (é detalhe de implementação do dedup, sem valor pra quem lê a
resposta).

**Erros:**

| Situação | Status |
|---|---|
| `owner`/`textoVaga` ausentes ou vazios, `frente` ausente | 400 |
| `frente = TRANSVERSAL` | 400 |
| `owner` sem Profile | 404 |
| Falha na Claude API (seção 3.3) | 502 |

## 6. Cálculo em código (sem mudança em relação ao PRD)

```java
int aderenciaPct(List<RequisitoClassificado> requisitos) {
    double peso = requisitos.stream().mapToDouble(r -> switch (r.getClassificacao()) {
        case FORTE -> 1.0;
        case PARCIAL -> 0.5;
        case NENHUM -> 0.0;
    }).sum();
    return (int) Math.floor(peso / requisitos.size() * 100); // arredonda para baixo
}
```

Faixas de decisão e zonas de fronteira exatamente como o PRD (seções 6.4 e
6.6) — sem alteração, só transcrito em `enum Decisao` com um método
estático `Decisao.paraPct(int pct)`.

`revisar = true` quando: zona de fronteira, houve rebaixamento por
evidência inválida, análise inconclusiva (seção 3.2, lista vazia), ou
`frenteDetectada` diverge do parâmetro `frente`. A **causa** é só logada
via SLF4J (campos estruturados: `owner`, `vagaChave`, `causa`), não vira
coluna nova em `MatchResult` — decisão consciente: o PRD (seção 6.6) só
pede que a causa fique disponível pra calibrar o gatilho da fase 2, e log
já é suficiente pra isso sem crescer o schema. **Gatilho de revisão:** se
precisar consultar "quantos `revisar` por causa, por período" via API (não
só grep de log), aí sim promover a causa a coluna.

## 7. Dedup — fluxo no Service

```
buscar MatchResult por (profile, vagaChave)
  se existir e reanalisar != true → devolver (200), NÃO chama Claude
  se existir e reanalisar == true → chama Claude, sobrescreve a mesma linha (versaoPrompt/modelo/analisadoEm atualizados), devolve (201)
  se não existir → chama Claude, persiste nova linha (201)
```

Concorrência: `save` protegido pela UNIQUE `(profile_id, vaga_chave)`;
`DataIntegrityViolationException` capturada e tratada como "buscar de novo
e devolver o resultado salvo" (200) — não 409, diferente da F01, pela
idempotência que o coletor exige (PRD, seção 6.2, `Assumption`
confirmada).

## 8. Estratégia de teste

Mesmo racional de risco da F01 (`STATUS.md`, F01 Bloco 3): não é meta
cobertura de 100%.

- **Unitário (Mockito, `FitAnalysisClient` mockado):** cálculo de
  `aderenciaPct` e faixas (incluindo limites exatos e arredondamento para
  baixo), verificação de `evidencia_ref` (token válido de skill, de
  experiência, token malformado, token de item fora da frente), regra de
  `revisar` por cada causa isoladamente e combinada, dedup (existe sem
  `reanalisar`, existe com `reanalisar`, não existe), vaga sem
  requisitos.
- **Web-slice (`@WebMvcTest`):** casos representativos do endpoint —
  201 análise nova, 200 dedup, 400 `frente` inválida, 404 owner
  inexistente, 502 falha simulada do client.
- **Não coberto por teste automatizado:** qualidade real da classificação
  da Claude — isso é o conjunto de regressão de 10–15 vagas julgadas à
  mão (PRD, seção 5), processo manual, não JUnit. É o teste que só roda
  quando o prompt muda, e é o teste que os €5 de crédito vão viabilizar
  na prática.

## 9. Ordem de implementação sugerida

1. `Frente` já existe (F01) — reaproveitado sem mudança.
2. Entidade `MatchResult` + `RequisitoClassificado` + `GapRisco` +
   `Repository` (sem Claude ainda — só persistência e a UNIQUE).
3. Cálculo (`aderenciaPct`, `Decisao`, zonas de fronteira) e verificação de
   evidência — puro, testável sem nenhuma dependência externa.
4. `FitAnalysisClient` (interface) + `ClaudeFitAnalysisClient` (SDK real) +
   prompt/tool schema.
5. `MatchService` juntando tudo (filtro por frente, dedup, chamada ao
   client, verificação, cálculo, persistência).
6. `MatchController` + DTOs.

Ordem pensada pra validar a parte determinística (passos 2–3) com testes
antes de gastar qualquer crédito real de API no passo 4.
