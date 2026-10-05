package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Conjunto de regressão do prompt (PRD F02, seção 5): roda vagas julgadas à
 * mão contra a Claude API DE VERDADE e falha se a decisão se afastar 2 faixas
 * ou mais do julgamento humano. Gasta crédito (~5 chamadas por rodada).
 *
 * <p>Fora do {@code mvn test} normal (tag excluída no pom). Para rodar:
 * {@code ./mvnw test -Dgroups=regressao -DexcludedGroups= -Dtest=RegressaoPromptTest}
 *
 * <p>O que é testado: o prompt + a resposta da Claude. Por isso chama o
 * {@link ClaudeFitAnalysisClient} direto, sem Spring, banco ou dedup. Como o
 * client devolve só os requisitos classificados (a decisão é calculada em
 * código, no {@link MatchService}), este teste aplica as mesmas funções puras
 * da produção — {@link VerificadorEvidencia}, {@link VerificadorAnosMinimos},
 * {@link AderenciaCalculadora}, {@link Decisao#paraPct}, {@link Decisao#aplicarTeto} — para chegar na mesma decisão que o endpoint daria.
 *
 * <p>Frente: como no endpoint, o perfil é filtrado ANTES da chamada pela
 * frente informada (+ TRANSVERSAL), com os mesmos métodos do
 * {@link MatchService}. A frente detectada pela Claude só existe depois da
 * resposta, então não pode montar o prompt; se divergir da informada, sai um
 * AVISO (em produção isso marcaria {@code revisar}). Frente por caso em
 * {@code esperado.properties} ({@code vaga-NN.frente=COMEX}); padrão TECH.
 *
 * <p>Para rodar só alguns casos: {@code -Dregressao.casos=vaga-01,vaga-03}.
 *
 * <p>Fixtures: as REAIS ficam em {@code regressao-local/} na raiz do projeto
 * (ignorada pelo git; outra pasta com {@code -Dregressao.dir=<caminho>}). Sem
 * {@code esperado.properties} nela, usa os exemplos versionados de
 * {@code src/test/resources/regressao/}. Nunca mistura as duas fontes (ver
 * {@link #escolherFonte}). Caso novo = um {@code vaga-NN.txt} + uma linha em
 * {@code esperado.properties} + incluir o nome em {@link #CASOS}.
 */
@Tag("regressao")
class RegressaoPromptTest {

    private static final String PASTA = "/regressao/";
    // Relativo ao diretório de trabalho do Maven (a raiz do projeto).
    private static final Path DIR_LOCAL_PADRAO = Path.of("regressao-local");
    private static final List<String> CASOS = List.of("vaga-01", "vaga-02", "vaga-03", "vaga-04", "vaga-05");
    private static final String CASO_TECNOLOGIA_AUSENTE = "vaga-05";
    private static final Frente FRENTE_PADRAO = Frente.TECH;

    // Ordem do melhor para o pior. A distância entre duas decisões é a diferença
    // de posição nesta lista. Explícita aqui (e não Decisao.ordinal()) para o
    // teste não mudar de significado se alguém reordenar o enum.
    private static final List<Decisao> ORDEM = List.of(Decisao.CV_PRIORITARIO, Decisao.CV_CARTA,
            Decisao.CV_CARTA_COM_AVISO, Decisao.NAO_CANDIDATAR, Decisao.FORA_ESCOPO);

    // Até 1 faixa de diferença passa: a fronteira entre faixas vizinhas é
    // discutível até para o julgamento humano. 2 ou mais = erro de verdade.
    private static final int DISTANCIA_MAXIMA = 1;

    @Test
    void decisoesDaClaudeFicamPertoDoJulgamentoHumano() throws IOException {
        // Primeira linha da rodada, antes de qualquer checagem: mostra de onde vêm
        // as fixtures mesmo quando o teste acaba ignorado.
        fonte = escolherFonte(System.getProperty("regressao.dir"), DIR_LOCAL_PADRAO);
        System.out.println(fonte.local()
                ? "Fixtures da regressão: LOCAL em " + fonte.dir().toAbsolutePath()
                : "Fixtures da regressão: EXEMPLOS versionados (classpath " + PASTA + ")");

        // --- Pré-condições: sem elas o teste é IGNORADO, nunca falha ---
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY não definida — conjunto de regressão ignorado (ele chama a API real)");

        Properties esperado = carregarEsperado();
        List<String> vazios = new ArrayList<>();
        for (String caso : CASOS) {
            if (esperado.getProperty(caso, "").isBlank()) {
                vazios.add(caso);
            }
        }
        if (esperado.getProperty(CASO_TECNOLOGIA_AUSENTE + ".tecnologia-ausente", "").isBlank()) {
            vazios.add(CASO_TECNOLOGIA_AUSENTE + ".tecnologia-ausente");
        }
        assumeTrue(vazios.isEmpty(),
                "esperado.properties com valores vazios " + vazios + " — preencha antes de rodar; teste ignorado");

        // --- Execução: um caso por vez, coletando falhas para mostrar a tabela inteira ---
        Perfil perfil = carregarPerfil();
        ClaudeFitAnalysisClient client = new ClaudeFitAnalysisClient(modelo(), "regressao", 60);

        List<String> linhasTabela = new ArrayList<>();
        List<String> falhas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();

        // -Dregressao.casos=vaga-01 roda só os casos listados (cada caso = 1 chamada paga).
        String filtroCasos = System.getProperty("regressao.casos", "");
        List<String> casosRodar = filtroCasos.isBlank() ? CASOS : List.of(filtroCasos.split(","));

        for (String caso : casosRodar) {
            Decisao decisaoEsperada = parseDecisao(caso, esperado.getProperty(caso));
            String textoVaga = lerRecurso(caso + ".txt");
            Frente frente = parseFrente(caso, esperado.getProperty(caso + ".frente"));

            // Mesmo filtro do MatchService: vai no prompt e é o que o verificador aceita.
            List<Skill> skills = MatchService.filtrarSkills(perfil.skills(), frente);
            List<ExperienciaProfissional> experiencias = MatchService.filtrarExperiencias(perfil.experiencias(),
                    frente);

            FitAnalysisResult resposta = client.analisar(new FitAnalysisRequest(textoVaga, skills, experiencias));

            // Mesmas funções que o MatchService usa: rebaixa evidência inexistente,
            // calcula o percentual e a faixa.
            List<RequisitoClassificado> verificados = resposta.requisitos().stream()
                    .map(req -> VerificadorEvidencia.verificar(req, skills, experiencias))
                    .map(req -> VerificadorAnosMinimos.verificar(req, skills))
                    .toList();
            int aderenciaPct = AderenciaCalculadora.aderenciaPct(verificados);
            imprimirDiagnostico(caso, frente, skills.size(), experiencias.size(), resposta, verificados);
            if (resposta.frenteDetectada() != frente) {
                avisos.add("%s: frente informada %s, detectada %s".formatted(caso, frente,
                        resposta.frenteDetectada()));
            }
            Decisao decisaoObtida = Decisao.aplicarTeto(Decisao.paraPct(aderenciaPct), verificados);

            int distancia = Math.abs(ORDEM.indexOf(decisaoObtida) - ORDEM.indexOf(decisaoEsperada));
            boolean passou = distancia <= DISTANCIA_MAXIMA;
            if (!passou) {
                falhas.add("%s: esperado %s, obtido %s (distância %d)".formatted(caso, nome(decisaoEsperada),
                        nome(decisaoObtida), distancia));
            }

            if (caso.equals(CASO_TECNOLOGIA_AUSENTE)) {
                String tecnologia = esperado.getProperty(caso + ".tecnologia-ausente").trim();
                // Checado na resposta CRUA (antes do rebaixamento): a pergunta é se o
                // prompt induz a Claude a inventar cobertura, não se a rede de
                // proteção do código corrigiria depois.
                List<String> cobertos = resposta.requisitos().stream()
                        .filter(req -> req.getClassificacao() != Classificacao.NENHUM)
                        .filter(req -> contem(req.getDescricao(), tecnologia))
                        .map(req -> req.getDescricao() + " [" + req.getClassificacao() + ", "
                                + req.getEvidenciaRef() + "]")
                        .toList();
                if (!cobertos.isEmpty()) {
                    passou = false;
                    falhas.add("%s: '%s' aparece como requisito coberto: %s".formatted(caso, tecnologia, cobertos));
                }
                boolean emGaps = resposta.gapsRiscos().stream()
                        .anyMatch(gr -> contem(gr.getGap(), tecnologia) || contem(gr.getRisco(), tecnologia));
                if (!emGaps) {
                    avisos.add("%s: '%s' não aparece em gaps_riscos".formatted(caso, tecnologia));
                }
            }

            linhasTabela.add("%-9s | %-19s | %-19s | %13d | %s".formatted(caso, nome(decisaoEsperada),
                    nome(decisaoObtida), aderenciaPct, passou ? "passou" : "FALHOU"));
        }

        // --- Relatório no console (nada é persistido) ---
        System.out.println();
        System.out.println("Regressão do prompt — modelo " + modelo());
        System.out.println("caso      | esperado            | obtido              | aderencia_pct | resultado");
        System.out.println("----------+---------------------+---------------------+---------------+----------");
        linhasTabela.forEach(System.out::println);
        avisos.forEach(aviso -> System.out.println("AVISO: " + aviso));
        System.out.println();

        if (!falhas.isEmpty()) {
            fail("Regressão do prompt falhou em %d caso(s):%n- %s".formatted(falhas.size(),
                    String.join("\n- ", falhas)));
        }
    }

    /**
     * Detalhe por caso, antes da tabela: o que a Claude devolveu cru e o que o
     * verificador rebaixou. Serve para separar "prompt ruim" de "verificador
     * rejeitando" quando um caso falha.
     */
    private static void imprimirDiagnostico(String caso, Frente frente, int qtdSkills, int qtdExperiencias,
            FitAnalysisResult resposta, List<RequisitoClassificado> verificados) {
        System.out.println();
        System.out.println("=== DIAGNÓSTICO " + caso + " ===");
        System.out.println("frente usada no filtro: " + frente + " + TRANSVERSAL");
        System.out.println("frenteDetectada pela Claude: " + resposta.frenteDetectada());
        System.out.println("skills no prompt: " + qtdSkills + " | experiências no prompt: " + qtdExperiencias);
        System.out.println("requisitos devolvidos pela Claude: " + resposta.requisitos().size());
        for (int i = 0; i < resposta.requisitos().size(); i++) {
            RequisitoClassificado cru = resposta.requisitos().get(i);
            RequisitoClassificado ver = verificados.get(i);
            System.out.println("  [%d] %s%n      crua=%s evidencia=%s -> verificada=%s | eliminatorio=%s"
                    .formatted(i + 1, cru.getDescricao(), cru.getClassificacao(), cru.getEvidenciaRef(),
                            ver.getClassificacao(), cru.isEliminatorio())
                    + " tecnologia=%s anosMinimos=%s foraDoPerfil=%s".formatted(cru.getTecnologia(),
                            cru.getAnosMinimos(), cru.isForaDoPerfil()));
        }
        List<String> rejeitadas = new ArrayList<>();
        for (int i = 0; i < resposta.requisitos().size(); i++) {
            if (resposta.requisitos().get(i).getClassificacao() != verificados.get(i).getClassificacao()) {
                rejeitadas.add(String.valueOf(resposta.requisitos().get(i).getEvidenciaRef()));
            }
        }
        System.out.println("rebaixados (evidência ou anos): " + rejeitadas.size()
                + " | evidências rejeitadas: " + rejeitadas);
        System.out.println("gapsRiscos: " + resposta.gapsRiscos().size());
        resposta.gapsRiscos().forEach(g -> System.out.println("  - " + g.getGap() + " | " + g.getRisco()));
    }

    /** Mesmo modelo da aplicação: CLAUDE_MODEL ou o padrão do application.yml. */
    private String modelo() {
        String doAmbiente = System.getenv("CLAUDE_MODEL");
        return doAmbiente != null && !doAmbiente.isBlank() ? doAmbiente : "claude-haiku-4-5";
    }

    private Frente parseFrente(String caso, String valor) {
        if (valor == null || valor.isBlank()) {
            return FRENTE_PADRAO;
        }
        Frente frente;
        try {
            frente = Frente.valueOf(valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            frente = Frente.TRANSVERSAL; // cai no erro abaixo
        }
        if (frente == Frente.TRANSVERSAL) {
            // Mesma regra do endpoint: TRANSVERSAL não é frente de vaga.
            throw new IllegalArgumentException("esperado.properties: frente inválida em " + caso + ".frente='"
                    + valor + "'. Use TECH ou COMEX");
        }
        return frente;
    }

    private Decisao parseDecisao(String caso, String valor) {
        try {
            return Decisao.valueOf(valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Valor preenchido mas inválido é erro de digitação: falha (não ignora),
            // senão o caso sumiria da regressão sem ninguém perceber.
            throw new IllegalArgumentException("esperado.properties: valor inválido em " + caso + "='" + valor
                    + "'. Use um de: cv_prioritario, cv_carta, cv_carta_com_aviso, nao_candidatar, fora_escopo", e);
        }
    }

    private static String nome(Decisao decisao) {
        return decisao.name().toLowerCase(Locale.ROOT);
    }

    private static boolean contem(String texto, String trecho) {
        return texto != null && texto.toLowerCase(Locale.ROOT).contains(trecho.toLowerCase(Locale.ROOT));
    }

    // UTF-8 explícito: Properties.load(InputStream) usaria ISO-8859-1 e
    // estragaria acentos (ex: tecnologia-ausente=Serviços AWS).
    private Properties carregarEsperado() throws IOException {
        Properties props = new Properties();
        try (Reader leitor = new InputStreamReader(abrir("esperado.properties"), StandardCharsets.UTF_8)) {
            props.load(leitor);
        }
        return props;
    }

    private String lerRecurso(String arquivo) throws IOException {
        try (InputStream entrada = abrir(arquivo)) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Fonte da rodada atual; definida no início do teste.
    private Fonte fonte;

    private InputStream abrir(String arquivo) throws IOException {
        return abrir(fonte, arquivo);
    }

    /** De onde vêm TODAS as fixtures de uma rodada: pasta local ou exemplos do classpath. */
    record Fonte(boolean local, Path dir) {
    }

    /**
     * Regra da fonte, sem mistura por arquivo:
     * <ul>
     * <li>{@code dirPropriedade} (-Dregressao.dir) informado: a pasta TEM de ter
     * {@code esperado.properties}, senão falha — quem indicou uma pasta espera
     * usá-la, e cair nos exemplos em silêncio esconderia um caminho errado.</li>
     * <li>Sem ele: {@code dirPadrao} (regressao-local/) se tiver
     * {@code esperado.properties}; senão os exemplos do classpath.</li>
     * </ul>
     */
    static Fonte escolherFonte(String dirPropriedade, Path dirPadrao) {
        if (dirPropriedade != null && !dirPropriedade.isBlank()) {
            Path dir = Path.of(dirPropriedade);
            if (!Files.isRegularFile(dir.resolve("esperado.properties"))) {
                throw new IllegalStateException("-Dregressao.dir=" + dirPropriedade
                        + " não tem esperado.properties (" + dir.toAbsolutePath() + ")");
            }
            return new Fonte(true, dir);
        }
        if (Files.isRegularFile(dirPadrao.resolve("esperado.properties"))) {
            return new Fonte(true, dirPadrao);
        }
        return new Fonte(false, null);
    }

    /** Lê um arquivo SÓ da fonte escolhida: arquivo ausente na pasta local é erro, nunca cai no exemplo. */
    static InputStream abrir(Fonte fonte, String arquivo) throws IOException {
        if (fonte.local()) {
            Path caminho = fonte.dir().resolve(arquivo);
            if (!Files.isRegularFile(caminho)) {
                throw new IllegalStateException("Fixture local não encontrada: " + caminho.toAbsolutePath()
                        + " (a fonte é local: os exemplos do classpath não são usados)");
            }
            return Files.newInputStream(caminho);
        }
        InputStream entrada = RegressaoPromptTest.class.getResourceAsStream(PASTA + arquivo);
        if (entrada == null) {
            throw new IllegalStateException("Fixture não encontrado: src/test/resources" + PASTA + arquivo);
        }
        return entrada;
    }

    /**
     * Monta Skill/ExperienciaProfissional a partir do JSON, pelos construtores
     * da produção. O id da experiência é atribuído por reflexão porque fora do
     * banco ele seria null, e o prompt e o VerificadorEvidencia dependem dele
     * ({@code [exp:ID]}).
     */
    private Perfil carregarPerfil() throws IOException {
        JsonNode raiz;
        try (InputStream entrada = abrir("profile-teste.json")) {
            raiz = new ObjectMapper().readTree(entrada);
        }

        List<Skill> skills = new ArrayList<>();
        for (JsonNode s : raiz.path("skills")) {
            skills.add(new Skill(s.path("nome").asText(), s.path("anosExperiencia").asInt(),
                    Frente.valueOf(s.path("frente").asText())));
        }

        List<ExperienciaProfissional> experiencias = new ArrayList<>();
        for (JsonNode e : raiz.path("experiencias")) {
            ExperienciaProfissional exp = new ExperienciaProfissional(e.path("empresa").asText(),
                    e.path("cargo").asText(), Frente.valueOf(e.path("frente").asText()),
                    LocalDate.parse(e.path("dataInicio").asText()),
                    e.hasNonNull("dataFim") ? LocalDate.parse(e.path("dataFim").asText()) : null);
            List<String> tecnologias = new ArrayList<>();
            e.path("tecnologiasUsadas").forEach(t -> tecnologias.add(t.asText()));
            exp.setTecnologiasUsadas(tecnologias);
            ReflectionTestUtils.setField(exp, "id", e.path("id").asLong());
            experiencias.add(exp);
        }
        return new Perfil(skills, experiencias);
    }

    private record Perfil(List<Skill> skills, List<ExperienciaProfissional> experiencias) {
    }
}
