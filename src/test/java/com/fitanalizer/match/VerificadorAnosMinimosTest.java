package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Skill;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificadorAnosMinimosTest {

    private final List<Skill> skills = List.of(new Skill("Java", 1, Frente.TECH), new Skill("MySQL", 2, Frente.TECH));

    private RequisitoClassificado exigindo(String tecnologia, int anosMinimos, Classificacao classificacao) {
        return new RequisitoClassificado("3+ anos de " + tecnologia, classificacao, "skill:" + tecnologia, true,
                tecnologia, anosMinimos, false);
    }

    @Test
    void anosInsuficientesRebaixaMesmoComClaudeDizendoParcial() {
        // Caso real da vaga-05: "superior a 3 anos de Java" com 1 ano no Profile veio PARCIAL.
        RequisitoClassificado verificado = VerificadorAnosMinimos.verificar(exigindo("Java", 3, Classificacao.PARCIAL),
                skills);

        assertThat(verificado.getClassificacao()).isEqualTo(Classificacao.NENHUM);
        assertThat(verificado.getEvidenciaRef()).isNull();
    }

    @Test
    void anosInsuficientesRebaixaMesmoComClaudeDizendoForte() {
        assertThat(VerificadorAnosMinimos.verificar(exigindo("Java", 3, Classificacao.FORTE), skills)
                .getClassificacao()).isEqualTo(Classificacao.NENHUM);
    }

    @Test
    void rebaixamentoMantemCamposDeJulgamento() {
        // O teto da decisão lê anosMinimos depois do rebaixamento.
        RequisitoClassificado verificado = VerificadorAnosMinimos.verificar(exigindo("Java", 3, Classificacao.PARCIAL),
                skills);

        assertThat(verificado.isEliminatorio()).isTrue();
        assertThat(verificado.getTecnologia()).isEqualTo("Java");
        assertThat(verificado.getAnosMinimos()).isEqualTo(3);
    }

    @Test
    void anosSuficientesMantem() {
        RequisitoClassificado requisito = exigindo("MySQL", 2, Classificacao.FORTE);

        assertThat(VerificadorAnosMinimos.verificar(requisito, skills)).isSameAs(requisito);
    }

    @Test
    void skillAusenteViraNenhum() {
        assertThat(VerificadorAnosMinimos.verificar(exigindo("Kafka", 1, Classificacao.PARCIAL), skills)
                .getClassificacao()).isEqualTo(Classificacao.NENHUM);
    }

    @Test
    void tecnologiaNulaComAnosMinimosViraNenhum() {
        RequisitoClassificado requisito = new RequisitoClassificado("3+ anos de backend", Classificacao.PARCIAL,
                "skill:Java", true, null, 3, false);

        assertThat(VerificadorAnosMinimos.verificar(requisito, skills).getClassificacao())
                .isEqualTo(Classificacao.NENHUM);
    }

    @Test
    void nomeDaSkillIgnoraMaiusculasEEspacos() {
        RequisitoClassificado requisito = exigindo(" mysql ", 2, Classificacao.FORTE);

        assertThat(VerificadorAnosMinimos.verificar(requisito, skills).getClassificacao())
                .isEqualTo(Classificacao.FORTE);
    }

    @Test
    void semAnosMinimosNaoVerificaNada() {
        RequisitoClassificado requisito = new RequisitoClassificado("Kafka", Classificacao.FORTE, "skill:Kafka");

        assertThat(VerificadorAnosMinimos.verificar(requisito, skills)).isSameAs(requisito);
    }
}
