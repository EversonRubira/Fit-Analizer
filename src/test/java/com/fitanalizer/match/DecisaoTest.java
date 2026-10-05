package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DecisaoTest {

    @Test
    void limitesDasFaixas() {
        assertThat(Decisao.paraPct(0)).isEqualTo(Decisao.FORA_ESCOPO);
        assertThat(Decisao.paraPct(29)).isEqualTo(Decisao.FORA_ESCOPO);
        assertThat(Decisao.paraPct(30)).isEqualTo(Decisao.NAO_CANDIDATAR);
        assertThat(Decisao.paraPct(49)).isEqualTo(Decisao.NAO_CANDIDATAR);
        assertThat(Decisao.paraPct(50)).isEqualTo(Decisao.CV_CARTA_COM_AVISO);
        assertThat(Decisao.paraPct(69)).isEqualTo(Decisao.CV_CARTA_COM_AVISO);
        assertThat(Decisao.paraPct(70)).isEqualTo(Decisao.CV_CARTA);
        assertThat(Decisao.paraPct(84)).isEqualTo(Decisao.CV_CARTA);
        assertThat(Decisao.paraPct(85)).isEqualTo(Decisao.CV_PRIORITARIO);
        assertThat(Decisao.paraPct(100)).isEqualTo(Decisao.CV_PRIORITARIO);
    }

    // --- Teto por eliminatório não atendido (Decisao.aplicarTeto) ---

    private static RequisitoClassificado anosNaoAtendidos() {
        return new RequisitoClassificado("3+ anos de Java", Classificacao.NENHUM, null, true, "Java", 3, false);
    }

    private static RequisitoClassificado forte(String descricao) {
        return new RequisitoClassificado(descricao, Classificacao.FORTE, "skill:" + descricao);
    }

    @Test
    void eliminatorioComAnosNaoAtendidoLimitaANaoCandidatar() {
        List<RequisitoClassificado> requisitos = List.of(anosNaoAtendidos(), forte("Git"), forte("SQL"));

        assertThat(Decisao.aplicarTeto(Decisao.CV_PRIORITARIO, requisitos)).isEqualTo(Decisao.NAO_CANDIDATAR);
        assertThat(Decisao.aplicarTeto(Decisao.CV_CARTA, requisitos)).isEqualTo(Decisao.NAO_CANDIDATAR);
        assertThat(Decisao.aplicarTeto(Decisao.CV_CARTA_COM_AVISO, requisitos)).isEqualTo(Decisao.NAO_CANDIDATAR);
    }

    @Test
    void tetoNuncaMelhoraADecisao() {
        assertThat(Decisao.aplicarTeto(Decisao.FORA_ESCOPO, List.of(anosNaoAtendidos())))
                .isEqualTo(Decisao.FORA_ESCOPO);
    }

    @Test
    void eliminatorioComAnosAtendidoNaoLimita() {
        RequisitoClassificado atendido = new RequisitoClassificado("3+ anos de Java", Classificacao.FORTE,
                "skill:Java", true, "Java", 3, false);

        assertThat(Decisao.aplicarTeto(Decisao.CV_CARTA, List.of(atendido))).isEqualTo(Decisao.CV_CARTA);
    }

    @Test
    void formacaoSemEvidenciaNaoAcionaOTeto() {
        // Formação não é eliminatório (o Profile não tem esse campo, de propósito): só liga revisar.
        RequisitoClassificado formacao = new RequisitoClassificado("Formação superior em Engenharia Informática",
                Classificacao.NENHUM, null, false, null, null, true);

        assertThat(Decisao.aplicarTeto(Decisao.CV_CARTA, List.of(formacao, forte("Git"))))
                .isEqualTo(Decisao.CV_CARTA);
    }

    @Test
    void senioridadeSemAnosNaoAcionaOTeto() {
        // Eliminatório, mas sem anosMinimos não há o que comparar com o Profile.
        RequisitoClassificado senior = new RequisitoClassificado("Perfil sênior", Classificacao.NENHUM, null, true,
                null, null, false);

        assertThat(Decisao.aplicarTeto(Decisao.CV_CARTA, List.of(senior))).isEqualTo(Decisao.CV_CARTA);
    }
}
