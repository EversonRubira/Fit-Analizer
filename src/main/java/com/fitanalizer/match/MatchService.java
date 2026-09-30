package com.fitanalizer.match;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Profile;
import com.fitanalizer.profile.ProfileNotFoundException;
import com.fitanalizer.profile.ProfileRepository;
import com.fitanalizer.profile.Skill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Junta o que o PRD/Spec da F02 divide entre Claude (julgamento) e código
 * (todo o resto): filtro por frente, dedup, verificação de evidência,
 * cálculo de aderência, faixa de decisão e marca de revisão.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchResultRepository matchResultRepository;
    private final ProfileRepository profileRepository;
    private final FitAnalysisClient fitAnalysisClient;
    private final String modelo;
    private final String versaoPrompt;

    public MatchService(MatchResultRepository matchResultRepository, ProfileRepository profileRepository,
            FitAnalysisClient fitAnalysisClient,
            @Value("${fitanalizer.claude.model}") String modelo,
            @Value("${fitanalizer.claude.prompt-version}") String versaoPrompt) {
        this.matchResultRepository = matchResultRepository;
        this.profileRepository = profileRepository;
        this.fitAnalysisClient = fitAnalysisClient;
        this.modelo = modelo;
        this.versaoPrompt = versaoPrompt;
    }

    @Transactional
    public AnaliseResultado analisar(String owner, String textoVaga, Frente frente, String vagaUrl,
            boolean reanalisar) {
        if (frente == Frente.TRANSVERSAL) {
            throw new FrenteInvalidaException(frente);
        }

        Profile profile = profileRepository.findByOwner(owner)
                .orElseThrow(() -> new ProfileNotFoundException(owner));
        // Mesmo achado da F01 (Spec, seção 2.3): coleções LAZY precisam ser
        // inicializadas ainda dentro da transação.
        Hibernate.initialize(profile.getSkills());
        Hibernate.initialize(profile.getHistoricoProfissional());

        String vagaChave = calcularVagaChave(textoVaga, vagaUrl);
        Optional<MatchResult> existente = matchResultRepository.findByProfileAndVagaChave(profile, vagaChave);

        if (existente.isPresent() && !reanalisar) {
            // Dedup: nenhuma chamada à Claude API (PRD F02, seção 6.2).
            return concluir(existente.get(), false);
        }

        List<Skill> skillsFiltrados = filtrarSkills(profile.getSkills(), frente);
        List<ExperienciaProfissional> experienciasFiltradas = filtrarExperiencias(profile.getHistoricoProfissional(),
                frente);

        FitAnalysisResult resultadoClaude = fitAnalysisClient
                .analisar(new FitAnalysisRequest(textoVaga, skillsFiltrados, experienciasFiltradas));

        AtomicBoolean houveRebaixamento = new AtomicBoolean(false);
        List<RequisitoClassificado> requisitosVerificados = resultadoClaude.requisitos().stream()
                .map(requisito -> verificar(requisito, skillsFiltrados, experienciasFiltradas, owner, vagaChave,
                        houveRebaixamento))
                .toList();

        boolean inconclusiva = requisitosVerificados.isEmpty();
        List<GapRisco> gapsRiscos = inconclusiva
                ? List.of(new GapRisco("sem requisitos obrigatórios identificáveis",
                        "análise inconclusiva, o número não vem de um cálculo real"))
                : resultadoClaude.gapsRiscos();

        int aderenciaPct = AderenciaCalculadora.aderenciaPct(requisitosVerificados);
        Decisao decisao = Decisao.paraPct(aderenciaPct);
        boolean frenteDivergente = resultadoClaude.frenteDetectada() != frente;
        boolean revisar = inconclusiva || houveRebaixamento.get() || frenteDivergente
                || AderenciaCalculadora.zonaDeFronteira(aderenciaPct);

        if (revisar) {
            logCausasRevisar(owner, vagaChave, inconclusiva, houveRebaixamento.get(), frenteDivergente, aderenciaPct);
        }

        MatchResult matchResult = existente.orElseGet(() -> new MatchResult(profile, frente, vagaChave, vagaUrl));
        matchResult.aplicarAnalise(aderenciaPct, requisitosVerificados, gapsRiscos, decisao, revisar, versaoPrompt,
                modelo, Instant.now());

        try {
            // saveAndFlush, não save: a violação da UNIQUE só é detectada no
            // INSERT/UPDATE real contra o banco. Com save() simples, o Hibernate
            // adia a escrita até o fim da transação (write-behind) — o
            // try/catch aqui embaixo não pegaria nada, porque o erro só
            // aconteceria depois deste método já ter retornado.
            matchResultRepository.saveAndFlush(matchResult);
        } catch (DataIntegrityViolationException e) {
            // Concorrência (PRD F02, seção 6.2): outra requisição da mesma vaga
            // já salvou entre o findByProfileAndVagaChave e este save. Devolve
            // o que ela salvou, não erro — o coletor precisa de idempotência.
            MatchResult jaSalvo = matchResultRepository.findByProfileAndVagaChave(profile, vagaChave)
                    .orElseThrow(() -> e);
            return concluir(jaSalvo, false);
        }

        return concluir(matchResult, true);
    }

    private AnaliseResultado concluir(MatchResult matchResult, boolean analiseNova) {
        // Mesmo achado da F01: requisitos/gapsRiscos são LAZY, o Controller monta
        // a resposta fora da transação.
        Hibernate.initialize(matchResult.getRequisitos());
        Hibernate.initialize(matchResult.getGapsRiscos());
        return new AnaliseResultado(matchResult, analiseNova);
    }

    private RequisitoClassificado verificar(RequisitoClassificado requisito, List<Skill> skills,
            List<ExperienciaProfissional> experiencias, String owner, String vagaChave,
            AtomicBoolean houveRebaixamento) {
        if (requisito.getClassificacao() == Classificacao.NENHUM) {
            return requisito;
        }
        if (VerificadorEvidencia.referenciaValida(requisito.getEvidenciaRef(), skills, experiencias)) {
            return requisito;
        }
        houveRebaixamento.set(true);
        log.warn("Alucinação detectada — owner={} vagaChave={} requisito=\"{}\" evidenciaRef={}", owner, vagaChave,
                requisito.getDescricao(), requisito.getEvidenciaRef());
        return requisito.rebaixarParaNenhum();
    }

    private void logCausasRevisar(String owner, String vagaChave, boolean inconclusiva, boolean houveRebaixamento,
            boolean frenteDivergente, int aderenciaPct) {
        List<String> causas = new ArrayList<>();
        if (inconclusiva) {
            causas.add("inconclusiva");
        }
        if (houveRebaixamento) {
            causas.add("evidencia_rebaixada");
        }
        if (frenteDivergente) {
            causas.add("frente_divergente");
        }
        if (AderenciaCalculadora.zonaDeFronteira(aderenciaPct)) {
            causas.add("zona_fronteira");
        }
        log.info("revisar=true owner={} vagaChave={} aderenciaPct={} causas={}", owner, vagaChave, aderenciaPct,
                causas);
    }

    /**
     * Filtro exigido em dois pontos pelo PRD (seção 6.4a): o que vai no prompt
     * (aqui) e o que o {@link VerificadorEvidencia} aceita como evidência
     * válida (mesma lista reaproveitada em {@code verificar}, sem duplicar o
     * filtro num segundo lugar que poderia divergir).
     */
    private List<Skill> filtrarSkills(List<Skill> skills, Frente frente) {
        return skills.stream()
                .filter(skill -> skill.getFrente() == frente || skill.getFrente() == Frente.TRANSVERSAL)
                .toList();
    }

    private List<ExperienciaProfissional> filtrarExperiencias(List<ExperienciaProfissional> experiencias,
            Frente frente) {
        return experiencias.stream()
                .filter(exp -> exp.getFrente() == frente || exp.getFrente() == Frente.TRANSVERSAL)
                .toList();
    }

    private String calcularVagaChave(String textoVaga, String vagaUrl) {
        if (vagaUrl != null && !vagaUrl.isBlank()) {
            return vagaUrl;
        }
        return "hash:" + sha256(normalizar(textoVaga));
    }

    private String normalizar(String texto) {
        return texto.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private String sha256(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é garantido pela especificação da JVM — nunca deveria acontecer.
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
