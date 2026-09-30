package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;

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
}
