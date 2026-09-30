package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Skill;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VerificadorEvidenciaTest {

    private final List<Skill> skills = List.of(new Skill("Java", 5, Frente.TECH));

    private ExperienciaProfissional experienciaComId(long id) {
        ExperienciaProfissional experiencia = new ExperienciaProfissional("Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null);
        ReflectionTestUtils.setField(experiencia, "id", id);
        return experiencia;
    }

    @Test
    void skillExistenteEValida() {
        assertThat(VerificadorEvidencia.referenciaValida("skill:Java", skills, List.of())).isTrue();
    }

    @Test
    void skillExistenteIgnorandoCaixaEEspacos() {
        assertThat(VerificadorEvidencia.referenciaValida("skill: java ", skills, List.of())).isTrue();
    }

    @Test
    void skillInexistenteEInvalida() {
        assertThat(VerificadorEvidencia.referenciaValida("skill:Python", skills, List.of())).isFalse();
    }

    @Test
    void experienciaExistenteEValida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(1L));
        assertThat(VerificadorEvidencia.referenciaValida("exp:1", List.of(), experiencias)).isTrue();
    }

    @Test
    void experienciaInexistenteEInvalida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(1L));
        assertThat(VerificadorEvidencia.referenciaValida("exp:99", List.of(), experiencias)).isFalse();
    }

    @Test
    void tokenDeExperienciaNaoNumericoEInvalido() {
        assertThat(VerificadorEvidencia.referenciaValida("exp:abc", List.of(), List.of())).isFalse();
    }

    @Test
    void tokenForaDoFormatoEsperadoEInvalido() {
        assertThat(VerificadorEvidencia.referenciaValida("Java", skills, List.of())).isFalse();
    }

    @Test
    void referenciaNulaOuVaziaEInvalida() {
        assertThat(VerificadorEvidencia.referenciaValida(null, skills, List.of())).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("  ", skills, List.of())).isFalse();
    }

    @Test
    void skillDeOutraFrenteNaoConta() {
        // simula o filtro já aplicado antes de chamar o verificador: a skill de COMEX
        // simplesmente não está na lista filtrada passada aqui.
        List<Skill> skillsFiltradosPorTech = List.of(); // nenhuma skill TECH nesse profile
        assertThat(VerificadorEvidencia.referenciaValida("skill:Negociação", skillsFiltradosPorTech, List.of()))
                .isFalse();
    }
}
