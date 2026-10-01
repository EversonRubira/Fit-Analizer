package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.JsonValue;
import com.fitanalizer.profile.Frente;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Testa só a conversão da resposta da Claude, sem chamar a API: a entrada da
 * ferramenta é montada à mão como um JsonValue, igual ao que o SDK devolve.
 */
class ClaudeFitAnalysisClientTest {

    @Test
    void converteEntradaDaFerramentaNoResultado() {
        // Por quê: foi exatamente isto que falhou na primeira chamada real (o código
        // lia o JsonValue via toString(), que não é JSON). Este teste teria pegado o bug.
        JsonValue entrada = JsonValue.from(Map.of(
                "frenteDetectada", "TECH",
                "requisitos", List.of(
                        Map.of("descricao", "Java", "classificacao", "forte", "evidenciaRef", "skill:Java"),
                        Map.of("descricao", "Kafka", "classificacao", "nenhum")),
                "gapsRiscos", List.of(Map.of("gap", "Kafka", "risco", "sem experiência"))));

        FitAnalysisResult resultado = ClaudeFitAnalysisClient.converter(entrada);

        assertThat(resultado.frenteDetectada()).isEqualTo(Frente.TECH);
        assertThat(resultado.requisitos()).containsExactly(
                new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java"),
                new RequisitoClassificado("Kafka", Classificacao.NENHUM, null));
        assertThat(resultado.gapsRiscos()).containsExactly(new GapRisco("Kafka", "sem experiência"));
    }

    @Test
    void entradaComValorForaDoSchemaLancaFitAnalysisException() {
        // Por quê: frente inexistente não pode virar resultado; tem que virar o erro tratado (502 no Controller).
        JsonValue entrada = JsonValue.from(Map.of(
                "frenteDetectada", "INEXISTENTE",
                "requisitos", List.of(),
                "gapsRiscos", List.of()));

        assertThatThrownBy(() -> ClaudeFitAnalysisClient.converter(entrada))
                .isInstanceOf(FitAnalysisException.class);
    }
}
