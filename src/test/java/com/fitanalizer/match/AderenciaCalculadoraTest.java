package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AderenciaCalculadoraTest {

    private RequisitoClassificado requisito(Classificacao classificacao) {
        return new RequisitoClassificado("requisito", classificacao, null);
    }

    @Test
    void todosFortesDevolve100() {
        List<RequisitoClassificado> requisitos = List.of(requisito(Classificacao.FORTE), requisito(Classificacao.FORTE));
        assertThat(AderenciaCalculadora.aderenciaPct(requisitos)).isEqualTo(100);
    }

    @Test
    void todosNenhumDevolveZero() {
        List<RequisitoClassificado> requisitos = List.of(requisito(Classificacao.NENHUM), requisito(Classificacao.NENHUM));
        assertThat(AderenciaCalculadora.aderenciaPct(requisitos)).isZero();
    }

    @Test
    void listaVaziaDevolveZero() {
        assertThat(AderenciaCalculadora.aderenciaPct(List.of())).isZero();
    }

    @Test
    void arredondaSempreParaBaixo() {
        // 2 fortes (peso 2.0) + 1 nenhum (peso 0) sobre 3 = 66.66...% -> 66, nunca 67
        List<RequisitoClassificado> requisitos = List.of(
                requisito(Classificacao.FORTE), requisito(Classificacao.FORTE), requisito(Classificacao.NENHUM));
        assertThat(AderenciaCalculadora.aderenciaPct(requisitos)).isEqualTo(66);
    }

    @Test
    void parcialContaMeioPeso() {
        // 1 forte (1.0) + 1 parcial (0.5) sobre 2 = 75%
        List<RequisitoClassificado> requisitos = List.of(requisito(Classificacao.FORTE), requisito(Classificacao.PARCIAL));
        assertThat(AderenciaCalculadora.aderenciaPct(requisitos)).isEqualTo(75);
    }

    @Test
    void zonasDeFronteira() {
        assertThat(AderenciaCalculadora.zonaDeFronteira(27)).isFalse();
        assertThat(AderenciaCalculadora.zonaDeFronteira(28)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(32)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(33)).isFalse();

        assertThat(AderenciaCalculadora.zonaDeFronteira(48)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(52)).isTrue();

        assertThat(AderenciaCalculadora.zonaDeFronteira(68)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(72)).isTrue();

        assertThat(AderenciaCalculadora.zonaDeFronteira(83)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(87)).isTrue();
        assertThat(AderenciaCalculadora.zonaDeFronteira(88)).isFalse();

        assertThat(AderenciaCalculadora.zonaDeFronteira(50)).isTrue(); // dentro de 48-52, mesma faixa de decisao
        assertThat(AderenciaCalculadora.zonaDeFronteira(0)).isFalse();
        assertThat(AderenciaCalculadora.zonaDeFronteira(100)).isFalse();
    }
}
