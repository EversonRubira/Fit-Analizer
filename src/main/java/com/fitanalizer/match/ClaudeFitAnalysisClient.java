package com.fitanalizer.match;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Skill;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implementação real de {@link FitAnalysisClient} via Claude API, usando o
 * SDK oficial (Spec F02, seção 1.1). Forma a resposta como *tool use*
 * (seção 3.2), não como "responda em JSON" em texto livre.
 *
 * <p><b>Histórico de verificação:</b> a primeira versão desta classe (PR
 * #18) tinha dois erros de compilação — {@code tools(List<Tool>)} em vez de
 * {@code tools(List<ToolUnion>)} (union precisa de {@code ToolUnion.ofTool(...)})
 * e {@code toolUse.input()} em vez de {@code toolUse._input()} (accessor com
 * underscore, padrão do SDK pra campos {@code JsonValue}) — confirmados
 * contra o código-fonte real do SDK depois do erro reportado pelo Everson,
 * e corrigidos. O restante (construção do {@code Tool.InputSchema},
 * {@code .apiKey(...)}/{@code .timeout(...)} do client, {@code .model(String)})
 * segue sem confirmação por compilação real neste ambiente (sandbox sem
 * Maven Central) — se `mvn compile` falhar de novo, mandar o erro.
 */
@Component
public class ClaudeFitAnalysisClient implements FitAnalysisClient {

    private static final String NOME_FERRAMENTA = "classificar_fit";

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String modelo;
    private final String versaoPrompt;

    public ClaudeFitAnalysisClient(
            @Value("${fitanalizer.claude.model}") String modelo,
            @Value("${fitanalizer.claude.prompt-version}") String versaoPrompt,
            @Value("${fitanalizer.claude.timeout-seconds}") long timeoutSegundos) {
        this.modelo = modelo;
        this.versaoPrompt = versaoPrompt;
        this.objectMapper = new ObjectMapper();
        // ANTHROPIC_API_KEY é lida do ambiente pelo próprio SDK (nunca hardcoded
        // aqui — Spec F02, seção 1.2). .timeout(...) não verificado por
        // compilação real, ver aviso no topo da classe.
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .build();
    }

    @Override
    public FitAnalysisResult analisar(FitAnalysisRequest request) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(modelo)
                    .maxTokens(4096L)
                    .system(montarPrompt(request))
                    .addUserMessage(request.textoVaga())
                    .tools(List.of(ToolUnion.ofTool(construirFerramenta())))
                    .toolChoice(ToolChoiceTool.builder().name(NOME_FERRAMENTA).build())
                    .build();

            Message resposta = client.messages().create(params);
            return converter(extrairEntradaDaFerramenta(resposta));
        } catch (FitAnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new FitAnalysisException("Falha ao chamar a Claude API", e);
        }
    }

    private String montarPrompt(FitAnalysisRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Você analisa uma vaga de emprego contra um perfil técnico estruturado.

                Sua única tarefa: extrair TODOS os requisitos OBRIGATÓRIOS da vaga
                (inclusive os sem cobertura) e classificar cada um pela cobertura do
                perfil abaixo, com "forte", "parcial" ou "nenhum". NÃO calcule
                percentual nem decisão — isso é feito em código, fora desta análise.

                Regra mais importante: nunca invente evidência. Para cada requisito
                "forte" ou "parcial", cite em evidenciaRef o token EXATO do item do
                perfil abaixo que sustenta a classificação — nunca reescreva o nome.
                Se não houver evidência real no perfil, classifique "nenhum" e deixe
                evidenciaRef vazio.

                Requisitos desejáveis (não obrigatórios) não entram na lista.

                Classifique também a frente da vaga (COMEX ou TECH) a partir do
                próprio texto, em frenteDetectada.

                Perfil disponível (use o token entre colchetes como evidenciaRef):
                """);
        request.skills().forEach(skill -> prompt.append("- [skill:%s] %s, %d anos%n"
                .formatted(skill.getNome(), skill.getNome(), skill.getAnosExperiencia())));
        request.experiencias().forEach(exp -> prompt.append("- [exp:%d] %s como %s%n"
                .formatted(exp.getId(), exp.getCargo(), exp.getEmpresa())));
        return prompt.toString();
    }

    private Tool construirFerramenta() {
        Map<String, Object> propriedadesRequisito = new LinkedHashMap<>();
        propriedadesRequisito.put("descricao", Map.of("type", "string"));
        propriedadesRequisito.put("classificacao",
                Map.of("type", "string", "enum", List.of("forte", "parcial", "nenhum")));
        propriedadesRequisito.put("evidenciaRef", Map.of("type", List.of("string", "null")));

        Map<String, Object> schemaRequisito = Map.of(
                "type", "object",
                "properties", propriedadesRequisito,
                "required", List.of("descricao", "classificacao"));

        Map<String, Object> schemaGapRisco = Map.of(
                "type", "object",
                "properties", Map.of("gap", Map.of("type", "string"), "risco", Map.of("type", "string")),
                "required", List.of("gap", "risco"));

        Map<String, Object> propriedades = new LinkedHashMap<>();
        propriedades.put("frenteDetectada", Map.of("type", "string", "enum", List.of("COMEX", "TECH")));
        propriedades.put("requisitos", Map.of("type", "array", "items", schemaRequisito));
        propriedades.put("gapsRiscos", Map.of("type", "array", "items", schemaGapRisco));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", propriedades,
                "required", List.of("frenteDetectada", "requisitos", "gapsRiscos"));

        // Ponto não verificado por compilação real — ver aviso no topo da classe.
        return Tool.builder()
                .name(NOME_FERRAMENTA)
                .description("Classifica os requisitos obrigatórios da vaga pela cobertura do perfil informado.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(JsonValue.from(schema.get("properties")))
                        .putAdditionalProperty("required", JsonValue.from(schema.get("required")))
                        .build())
                .build();
    }

    // Ponto não verificado por compilação real — ver aviso no topo da classe.
    private ToolUseBlock extrairEntradaDaFerramenta(Message resposta) {
        return resposta.content().stream()
                .flatMap(bloco -> bloco.toolUse().stream())
                .findFirst()
                .orElseThrow(() -> new FitAnalysisException("A Claude não usou a ferramenta esperada na resposta"));
    }

    private FitAnalysisResult converter(ToolUseBlock toolUse) {
        try {
            ClaudeToolResponse resposta = objectMapper.readValue(toolUse._input().toString(), ClaudeToolResponse.class);
            List<RequisitoClassificado> requisitos = resposta.requisitos().stream()
                    .map(r -> new RequisitoClassificado(r.descricao(), Classificacao.valueOf(r.classificacao().toUpperCase()),
                            r.evidenciaRef()))
                    .toList();
            List<GapRisco> gapsRiscos = resposta.gapsRiscos().stream()
                    .map(g -> new GapRisco(g.gap(), g.risco()))
                    .toList();
            return new FitAnalysisResult(Frente.valueOf(resposta.frenteDetectada()), requisitos, gapsRiscos);
        } catch (Exception e) {
            throw new FitAnalysisException("Resposta da Claude fora do schema esperado", e);
        }
    }

    /** Espelha o schema pedido à Claude (seção acima) — só para desserialização via Jackson. */
    private record ClaudeToolResponse(String frenteDetectada, List<RequisitoDto> requisitos,
            List<GapRiscoDto> gapsRiscos) {

        record RequisitoDto(String descricao, String classificacao, String evidenciaRef) {
        }

        record GapRiscoDto(String gap, String risco) {
        }
    }
}
