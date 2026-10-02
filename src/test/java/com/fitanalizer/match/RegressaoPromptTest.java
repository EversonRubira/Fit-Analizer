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
 * da produção — {@link VerificadorEvidencia}, {@link AderenciaCalculadora},
 * {@link Decisao#paraPct} — para chegar na mesma decisão que o endpoint daria.
 *
 * <p>Fixtures em {@code src/test/resources/regressao/}. Caso novo = um
 * {@code vaga-NN.txt} + uma linha em {@code esperado.properties} + incluir o
 * nome em {@link #CASOS}.
 */
@Tag("regressao")
class RegressaoPromptTest {

    private static final String PASTA = "/regressao/";
    private static final List<String> CASOS = List.of("vaga-01", "vaga-02", "vaga-03", "vaga-04", "vaga-05");
    private static final String CASO_TECNOLOGIA_AUSENTE = "vaga-05";

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

        for (String caso : CASOS) {
            Decisao decisaoEsperada = parseDecisao(caso, esperado.getProperty(caso));
            String textoVaga = lerRecurso(caso + ".txt");

            FitAnalysisResult resposta = client.analisar(
                    new FitAnalysisRequest(textoVaga, perfil.skills(), perfil.experiencias()));

            // Mesmo cálculo do MatchService.calcular: rebaixa evidência inexistente,
            // calcula o percentual e a faixa. Se a regra de produção mudar lá, mudar aqui.
            List<RequisitoClassificado> verificados = resposta.requisitos().stream()
                    .map(req -> verificar(req, perfil))
                    .toList();
            int aderenciaPct = AderenciaCalculadora.aderenciaPct(verificados);
            Decisao decisaoObtida = Decisao.paraPct(aderenciaPct);

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

    // Réplica de MatchService.verificar: requisito forte/parcial cuja evidência
    // não existe no perfil vira "nenhum".
    private RequisitoClassificado verificar(RequisitoClassificado req, Perfil perfil) {
        if (req.getClassificacao() == Classificacao.NENHUM
                || VerificadorEvidencia.referenciaValida(req.getEvidenciaRef(), perfil.skills(),
                        perfil.experiencias())) {
            return req;
        }
        return req.rebaixarParaNenhum();
    }

    /** Mesmo modelo da aplicação: CLAUDE_MODEL ou o padrão do application.yml. */
    private String modelo() {
        String doAmbiente = System.getenv("CLAUDE_MODEL");
        return doAmbiente != null && !doAmbiente.isBlank() ? doAmbiente : "claude-haiku-4-5";
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

    private InputStream abrir(String arquivo) {
        InputStream entrada = getClass().getResourceAsStream(PASTA + arquivo);
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
