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

    // --- Colchetes: o prompt lista o perfil como [skill:Java] / [exp:2] e a Claude
    // às vezes devolve o token assim. Só UM par externo + trim é aceito.

    @Test
    void skillComColchetesEValida() {
        assertThat(VerificadorEvidencia.referenciaValida("[skill:Java]", skills, List.of())).isTrue();
    }

    @Test
    void experienciaComColchetesEValida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("[exp:2]", List.of(), experiencias)).isTrue();
    }

    @Test
    void experienciaSemColchetesContinuaValida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("exp:2", List.of(), experiencias)).isTrue();
    }

    @Test
    void espacosEmVoltaDosColchetesSaoIgnorados() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("  [exp:2]  ", List.of(), experiencias)).isTrue();
        assertThat(VerificadorEvidencia.referenciaValida("  exp:2 ", List.of(), experiencias)).isTrue();
    }

    @Test
    void referenciaInexistenteComColchetesContinuaInvalida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("[exp:99]", List.of(), experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("[skill:Kafka]", skills, List.of())).isFalse();
    }

    @Test
    void prefixoDesconhecidoComColchetesContinuaInvalido() {
        assertThat(VerificadorEvidencia.referenciaValida("[tech:Java]", skills, List.of())).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("[Java]", skills, List.of())).isFalse();
    }

    @Test
    void outrasVariacoesDeColchetesNaoSaoAceitas() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("[[exp:2]]", List.of(), experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("[exp:2", List.of(), experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("exp:2]", List.of(), experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("(exp:2)", List.of(), experiencias)).isFalse();
    }

    @Test
    void verificarMantemClassificacaoEGravaReferenciaSemColchetes() {
        RequisitoClassificado requisito = new RequisitoClassificado("Java", Classificacao.FORTE, "[skill:Java]");

        RequisitoClassificado verificado = VerificadorEvidencia.verificar(requisito, skills, List.of());

        assertThat(verificado.getClassificacao()).isEqualTo(Classificacao.FORTE);
        assertThat(verificado.getEvidenciaRef()).isEqualTo("skill:Java");
    }

    // --- Lista separada por vírgula (prompt v3 devolvia "skill:Docker, exp:1") ---

    @Test
    void listaComTodosOsTokensValidosMantemEGravaListaNormalizada() {
        // Por quê: todos os tokens existem no perfil, então a classificação se mantém;
        // grava sem colchetes e com separador padronizado.
        RequisitoClassificado requisito = new RequisitoClassificado("Java", Classificacao.FORTE,
                "skill:Java,[exp:2] ");

        RequisitoClassificado verificado = VerificadorEvidencia.verificar(requisito, skills,
                List.of(experienciaComId(2L)));

        assertThat(verificado.getClassificacao()).isEqualTo(Classificacao.FORTE);
        assertThat(verificado.getEvidenciaRef()).isEqualTo("skill:Java, exp:2");
    }

    @Test
    void listaComUmTokenInvalidoRebaixaEApontaQual() {
        // Por quê: caso real do v3 — um token inventado no meio de um real não pode
        // passar; e o log precisa dizer qual foi.
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(1L));
        RequisitoClassificado requisito = new RequisitoClassificado("Docker", Classificacao.PARCIAL,
                "skill:Docker, exp:1");

        assertThat(VerificadorEvidencia.verificar(requisito, skills, experiencias).getClassificacao())
                .isEqualTo(Classificacao.NENHUM);
        assertThat(VerificadorEvidencia.tokensInvalidos("skill:Docker, exp:1", skills, experiencias))
                .containsExactly("skill:Docker");
    }

    @Test
    void listaComColchetesEmCadaTokenEValida() {
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));

        assertThat(VerificadorEvidencia.referenciaValida("[skill:Java], [exp:2]", skills, experiencias)).isTrue();
    }

    @Test
    void listaContinuaEstritaNoFormato() {
        // Colchetes valem por token, não em volta da lista inteira; token vazio é inválido.
        List<ExperienciaProfissional> experiencias = List.of(experienciaComId(2L));
        assertThat(VerificadorEvidencia.referenciaValida("[skill:Java, exp:2]", skills, experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("skill:Java,", skills, experiencias)).isFalse();
        assertThat(VerificadorEvidencia.referenciaValida("skill:Java; exp:2", skills, experiencias)).isFalse();
    }
}
