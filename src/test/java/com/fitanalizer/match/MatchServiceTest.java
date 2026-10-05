package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Profile;
import com.fitanalizer.profile.ProfileNotFoundException;
import com.fitanalizer.profile.ProfileRepository;
import com.fitanalizer.profile.Skill;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    @Mock
    private MatchResultRepository matchResultRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private FitAnalysisClient fitAnalysisClient;

    private MatchService service;

    @BeforeEach
    void setUp() {
        // Nos testes unitários não há banco: este template só executa o callback
        // direto, sem transação real. O comportamento transacional de verdade é
        // coberto por MatchDedupIntegrationTest (Postgres real).
        TransactionTemplate semTransacao = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        service = new MatchService(matchResultRepository, profileRepository, fitAnalysisClient, semTransacao,
                "claude-haiku-4-5", "v1");
    }

    private Profile perfilComSkillTech() {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Java", 5, Frente.TECH));
        profile.addSkill(new Skill("Negociação", 10, Frente.COMEX));
        return profile;
    }

    @Test
    void frenteTransversalLancaExcecaoAntesDeBuscarProfile() {
        assertThatThrownBy(() -> service.analisar("everson", "texto", Frente.TRANSVERSAL, null, false))
                .isInstanceOf(FrenteInvalidaException.class);
        verify(profileRepository, never()).findByOwner(any());
    }

    @Test
    void ownerSemProfileLancaExcecaoSemChamarClaude() {
        when(profileRepository.findByOwner("desconhecido")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analisar("desconhecido", "texto", Frente.TECH, null, false))
                .isInstanceOf(ProfileNotFoundException.class);

        verify(fitAnalysisClient, never()).analisar(any());
    }

    /** Linha já analisada com a versão de prompt informada ({@code null} = linha antiga, sem versão). */
    private MatchResult salvoComVersao(Profile profile, String versaoPrompt) {
        MatchResult salvo = new MatchResult(profile, Frente.TECH, "https://vaga.example/1", "https://vaga.example/1");
        salvo.aplicarAnalise(0, List.of(), List.of(), Decisao.FORA_ESCOPO, false, versaoPrompt, "claude-haiku-4-5",
                Instant.parse("2026-10-01T10:00:00Z"));
        return salvo;
    }

    @Test
    void dedupSemReanalisarDevolveResultadoExistenteSemChamarClaude() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        MatchResult existente = salvoComVersao(profile, "v1"); // mesma versão do service (setUp)
        when(matchResultRepository.findByProfileAndVagaChave(profile, "https://vaga.example/1"))
                .thenReturn(Optional.of(existente));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1",
                false);

        assertThat(resultado.analiseNova()).isFalse();
        assertThat(resultado.matchResult()).isSameAs(existente);
        verify(fitAnalysisClient, never()).analisar(any());
        verify(matchResultRepository, never()).saveAndFlush(any());
    }

    @Test
    void filtraSkillsEExperienciasPorFrenteAntesDeChamarClaude() {
        Profile profile = perfilComSkillTech();
        ExperienciaProfissional expTech = new ExperienciaProfissional("Empresa", "Dev", Frente.TECH,
                LocalDate.of(2023, 1, 1), null);
        ExperienciaProfissional expComex = new ExperienciaProfissional("Empresa2", "Analista", Frente.COMEX,
                LocalDate.of(2020, 1, 1), null);
        profile.addExperienciaProfissional(expTech);
        profile.addExperienciaProfissional(expComex);
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(), List.of()));

        service.analisar("everson", "texto da vaga", Frente.TECH, null, false);

        ArgumentCaptor<FitAnalysisRequest> captor = ArgumentCaptor.forClass(FitAnalysisRequest.class);
        verify(fitAnalysisClient).analisar(captor.capture());
        assertThat(captor.getValue().skills()).extracting(Skill::getNome).containsExactly("Java");
        assertThat(captor.getValue().experiencias()).containsExactly(expTech);
    }

    @Test
    void violacaoNaGravacaoSemResultadoNaReleituraRelancaAExcecaoOriginal() {
        // Por quê: a recuperação só vale para corrida de dedup (outra requisição
        // salvou a mesma vaga). Se a releitura não acha nada, a violação foi de
        // outra coisa e não pode ser mascarada como "resultado já existente".
        Profile profile = perfilComSkillTech();
        DataIntegrityViolationException original = new DataIntegrityViolationException("outra constraint");
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        when(fitAnalysisClient.analisar(any())).thenReturn(new FitAnalysisResult(Frente.TECH,
                List.of(new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java")), List.of()));
        when(matchResultRepository.saveAndFlush(any())).thenThrow(original);

        assertThatThrownBy(() -> service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1", false))
                .isSameAs(original);
    }

    @Test
    void vagaSemRequisitosPersisteInconclusiva() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "texto que não é vaga", Frente.TECH, null, false);

        MatchResult matchResult = resultado.matchResult();
        assertThat(matchResult.getAderenciaPct()).isZero();
        assertThat(matchResult.getDecisao()).isEqualTo(Decisao.FORA_ESCOPO);
        assertThat(matchResult.isRevisar()).isTrue();
        assertThat(matchResult.getGapsRiscos()).hasSize(1);
        assertThat(matchResult.getGapsRiscos().get(0).getGap()).isEqualTo("sem requisitos obrigatórios identificáveis");
    }

    @Test
    void evidenciaInexistenteNoProfileRebaixaERevisar() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        RequisitoClassificado requisitoInventado = new RequisitoClassificado("Kafka", Classificacao.FORTE,
                "skill:Kafka"); // não existe no profile
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(requisitoInventado), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "vaga de Kafka", Frente.TECH, null, false);

        MatchResult matchResult = resultado.matchResult();
        assertThat(matchResult.getRequisitos().get(0).getClassificacao()).isEqualTo(Classificacao.NENHUM);
        assertThat(matchResult.getRequisitos().get(0).getEvidenciaRef()).isNull();
        assertThat(matchResult.isRevisar()).isTrue();
        assertThat(matchResult.getAderenciaPct()).isZero();
    }

    @Test
    void evidenciaComColchetesNaoERebaixada() {
        // Regressão do bug real: a Claude devolve o token como aparece na listagem
        // do prompt ("[skill:Java]", "[exp:7]"). Isso não é alucinação.
        Profile profile = perfilComSkillTech();
        ExperienciaProfissional expTech = new ExperienciaProfissional("Empresa", "Dev", Frente.TECH,
                LocalDate.of(2024, 1, 1), null);
        ReflectionTestUtils.setField(expTech, "id", 7L);
        profile.addExperienciaProfissional(expTech);
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        when(fitAnalysisClient.analisar(any())).thenReturn(new FitAnalysisResult(Frente.TECH, List.of(
                new RequisitoClassificado("Java", Classificacao.FORTE, "[skill:Java]"),
                new RequisitoClassificado("Backend", Classificacao.PARCIAL, "[exp:7]")), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "vaga Java", Frente.TECH, null, false);

        MatchResult matchResult = resultado.matchResult();
        assertThat(matchResult.getRequisitos()).extracting(RequisitoClassificado::getClassificacao)
                .containsExactly(Classificacao.FORTE, Classificacao.PARCIAL);
        assertThat(matchResult.getRequisitos()).extracting(RequisitoClassificado::getEvidenciaRef)
                .containsExactly("skill:Java", "exp:7");
        assertThat(matchResult.getAderenciaPct()).isEqualTo(75);
        assertThat(matchResult.isRevisar()).isFalse();
    }

    private RequisitoClassificado forteJava(String descricao) {
        return new RequisitoClassificado(descricao, Classificacao.FORTE, "skill:Java");
    }

    @Test
    void anosAbaixoDoMinimoRebaixaAplicaTetoERevisar() {
        // Profile tem Java 5 anos; vaga exige 8. A Claude disse PARCIAL, o código rebaixa.
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        RequisitoClassificado anos = new RequisitoClassificado("8+ anos de Java", Classificacao.PARCIAL, "skill:java",
                true, "java", 8, false);
        when(fitAnalysisClient.analisar(any())).thenReturn(new FitAnalysisResult(Frente.TECH,
                List.of(anos, forteJava("A"), forteJava("B"), forteJava("C"), forteJava("D")), List.of()));

        MatchResult matchResult = service.analisar("everson", "vaga", Frente.TECH, null, false).matchResult();

        assertThat(matchResult.getRequisitos().get(0).getClassificacao()).isEqualTo(Classificacao.NENHUM);
        assertThat(matchResult.getAderenciaPct()).isEqualTo(80); // sozinho daria cv_carta
        assertThat(matchResult.getDecisao()).isEqualTo(Decisao.NAO_CANDIDATAR);
        assertThat(matchResult.isRevisar()).isTrue();
    }

    @Test
    void anosSuficientesNaoRebaixamNemLimitam() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        RequisitoClassificado anos = new RequisitoClassificado("3+ anos de Java", Classificacao.FORTE, "skill:Java",
                true, "Java", 3, false);
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(anos), List.of()));

        MatchResult matchResult = service.analisar("everson", "vaga", Frente.TECH, null, false).matchResult();

        assertThat(matchResult.getRequisitos().get(0).getClassificacao()).isEqualTo(Classificacao.FORTE);
        assertThat(matchResult.getDecisao()).isEqualTo(Decisao.CV_PRIORITARIO);
        assertThat(matchResult.isRevisar()).isFalse();
    }

    @Test
    void formacaoSemEvidenciaRevisarSemLimitarDecisao() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        RequisitoClassificado formacao = new RequisitoClassificado("Formação superior", Classificacao.NENHUM, null,
                false, null, null, true);
        when(fitAnalysisClient.analisar(any())).thenReturn(new FitAnalysisResult(Frente.TECH,
                List.of(formacao, forteJava("A"), forteJava("B"), forteJava("C"), forteJava("D")), List.of()));

        MatchResult matchResult = service.analisar("everson", "vaga", Frente.TECH, null, false).matchResult();

        assertThat(matchResult.getAderenciaPct()).isEqualTo(80);
        assertThat(matchResult.getDecisao()).isEqualTo(Decisao.CV_CARTA);
        assertThat(matchResult.isRevisar()).isTrue();
    }

    @Test
    void frenteDetectadaDivergenteMarcaRevisar() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(matchResultRepository.findByProfileAndVagaChave(any(), any())).thenReturn(Optional.empty());
        RequisitoClassificado requisito = new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java");
        // Claude detecta COMEX, mas o parâmetro informado foi TECH
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.COMEX, List.of(requisito), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, null, false);

        assertThat(resultado.matchResult().isRevisar()).isTrue();
        // aderenciaPct e classificacao não são afetados pela divergência de frente
        assertThat(resultado.matchResult().getAderenciaPct()).isEqualTo(100);
    }

    @Test
    void versaoDePromptDiferenteChamaClaudeESobrescreveMesmaLinha() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        MatchResult existente = salvoComVersao(profile, "v0"); // service roda com v1
        when(matchResultRepository.findByProfileAndVagaChave(profile, "https://vaga.example/1"))
                .thenReturn(Optional.of(existente));
        RequisitoClassificado requisito = new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java");
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(requisito), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1",
                false);

        verify(fitAnalysisClient).analisar(any());
        assertThat(resultado.analiseNova()).isTrue();
        assertThat(resultado.matchResult()).isSameAs(existente); // mesma linha, não um INSERT novo
        assertThat(existente.getVersaoPrompt()).isEqualTo("v1");
        assertThat(existente.getAderenciaPct()).isEqualTo(100);
        verify(matchResultRepository).saveAndFlush(existente);
    }

    @Test
    void versaoDePromptNulaChamaClaudeESobrescreveMesmaLinha() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        MatchResult existente = salvoComVersao(profile, null);
        when(matchResultRepository.findByProfileAndVagaChave(profile, "https://vaga.example/1"))
                .thenReturn(Optional.of(existente));
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1",
                false);

        verify(fitAnalysisClient).analisar(any());
        assertThat(resultado.analiseNova()).isTrue();
        assertThat(resultado.matchResult()).isSameAs(existente);
        assertThat(existente.getVersaoPrompt()).isEqualTo("v1");
    }

    @Test
    void reanalisarIgnoraDedupESobrescreveMesmaLinha() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        MatchResult existente = new MatchResult(profile, Frente.TECH, "https://vaga.example/1", "https://vaga.example/1");
        when(matchResultRepository.findByProfileAndVagaChave(profile, "https://vaga.example/1"))
                .thenReturn(Optional.of(existente));
        RequisitoClassificado requisito = new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java");
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(requisito), List.of()));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1",
                true);

        assertThat(resultado.analiseNova()).isTrue();
        assertThat(resultado.matchResult()).isSameAs(existente); // mesma linha, não uma nova
        verify(fitAnalysisClient).analisar(any());
    }

    @Test
    void violacaoDaUniqueNaGravacaoDevolveOResultadoDaOutraRequisicao() {
        Profile profile = perfilComSkillTech();
        when(profileRepository.findByOwner("everson")).thenReturn(Optional.of(profile));
        MatchResult salvoPelaOutra = new MatchResult(profile, Frente.TECH, "https://vaga.example/1",
                "https://vaga.example/1");
        // 1ª consulta (etapa 1): ainda não existe. 2ª (recuperação): a outra requisição já salvou.
        when(matchResultRepository.findByProfileAndVagaChave(profile, "https://vaga.example/1"))
                .thenReturn(Optional.empty(), Optional.of(salvoPelaOutra));
        when(fitAnalysisClient.analisar(any()))
                .thenReturn(new FitAnalysisResult(Frente.TECH, List.of(), List.of()));
        when(matchResultRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

        AnaliseResultado resultado = service.analisar("everson", "texto", Frente.TECH, "https://vaga.example/1",
                false);

        assertThat(resultado.analiseNova()).isFalse();
        assertThat(resultado.matchResult()).isSameAs(salvoPelaOutra);
    }
}
