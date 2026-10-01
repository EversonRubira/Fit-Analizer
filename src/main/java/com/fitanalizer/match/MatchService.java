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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Junta o que o PRD/Spec da F02 divide entre Claude (julgamento) e código
 * (todo o resto): filtro por frente, dedup, verificação de evidência,
 * cálculo de aderência, faixa de decisão e marca de revisão.
 *
 * <p><b>Transações:</b> {@link #analisar} deliberadamente NÃO é {@code @Transactional}.
 * Ele roda em três etapas: (1) transação curta de leitura + checagem de dedup;
 * (2) chamada à Claude API e cálculos, sem transação e sem conexão de banco
 * presa; (3) transação curta só para gravar. Se a gravação violar a UNIQUE
 * (duas requisições simultâneas da mesma vaga), o Postgres aborta a transação
 * e a sessão do Hibernate fica inutilizável — por isso a recuperação (ler o
 * que a outra requisição salvou) acontece numa transação NOVA, fora da que
 * falhou.
 *
 * <p><b>NÃO anotar com {@code @Transactional} nem chamar de dentro de outra
 * transação.</b> Os {@code TransactionTemplate} entrariam na transação externa
 * e a releitura após violação de constraint quebraria.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchResultRepository matchResultRepository;
    private final ProfileRepository profileRepository;
    private final FitAnalysisClient fitAnalysisClient;
    private final TransactionTemplate transactionTemplate;
    private final String modelo;
    private final String versaoPrompt;

    public MatchService(MatchResultRepository matchResultRepository, ProfileRepository profileRepository,
            FitAnalysisClient fitAnalysisClient, TransactionTemplate transactionTemplate,
            @Value("${fitanalizer.claude.model}") String modelo,
            @Value("${fitanalizer.claude.prompt-version}") String versaoPrompt) {
        this.matchResultRepository = matchResultRepository;
        this.profileRepository = profileRepository;
        this.fitAnalysisClient = fitAnalysisClient;
        this.transactionTemplate = transactionTemplate;
        this.modelo = modelo;
        this.versaoPrompt = versaoPrompt;
    }

    public AnaliseResultado analisar(String owner, String textoVaga, Frente frente, String vagaUrl,
            boolean reanalisar) {
        if (frente == Frente.TRANSVERSAL) {
            throw new FrenteInvalidaException(frente);
        }

        // Etapa 1 — transação curta: profile + dedup.
        Preparo preparo = transactionTemplate
                .execute(status -> preparar(owner, textoVaga, frente, vagaUrl, reanalisar));
        if (preparo.dedup() != null) {
            return preparo.dedup();
        }

        // Etapa 2 — sem transação: chamada à Claude (até 60s) e cálculos puros.
        Calculo calculo = calcular(owner, preparo, textoVaga, frente);

        // Etapa 3 — transação curta só para gravar.
        try {
            return transactionTemplate.execute(status -> gravar(preparo, frente, vagaUrl, reanalisar, calculo));
        } catch (DataIntegrityViolationException e) {
            // Concorrência (PRD F02, seção 6.2): outra requisição da mesma vaga
            // salvou entre a etapa 1 e a etapa 3. A transação da etapa 3 já foi
            // descartada; esta leitura roda numa transação nova e limpa. Devolve
            // o que a outra salvou, não erro — o coletor precisa de idempotência.
            // Se a releitura não achar nada, não foi corrida de dedup: relança a
            // exceção original (orElseThrow) em vez de mascará-la.
            return transactionTemplate.execute(status -> matchResultRepository
                    .findByProfileAndVagaChave(preparo.profile(), preparo.vagaChave())
                    .map(jaSalvo -> concluir(jaSalvo, false))
                    .orElseThrow(() -> e));
        }
    }

    private Preparo preparar(String owner, String textoVaga, Frente frente, String vagaUrl, boolean reanalisar) {
        Profile profile = profileRepository.findByOwner(owner)
                .orElseThrow(() -> new ProfileNotFoundException(owner));
        // Mesmo achado da F01 (Spec, seção 2.3): coleções LAZY precisam ser
        // inicializadas ainda dentro da transação — aqui, porque a etapa 2 usa
        // o profile já fora dela.
        Hibernate.initialize(profile.getSkills());
        Hibernate.initialize(profile.getHistoricoProfissional());

        String vagaChave = calcularVagaChave(textoVaga, vagaUrl);

        if (!reanalisar) {
            Optional<MatchResult> existente = matchResultRepository.findByProfileAndVagaChave(profile, vagaChave);
            if (existente.isPresent()) {
                // Dedup: nenhuma chamada à Claude API (PRD F02, seção 6.2).
                return new Preparo(profile, vagaChave, List.of(), List.of(), concluir(existente.get(), false));
            }
        }

        return new Preparo(profile, vagaChave, filtrarSkills(profile.getSkills(), frente),
                filtrarExperiencias(profile.getHistoricoProfissional(), frente), null);
    }

    private Calculo calcular(String owner, Preparo preparo, String textoVaga, Frente frente) {
        FitAnalysisResult resultadoClaude = fitAnalysisClient
                .analisar(new FitAnalysisRequest(textoVaga, preparo.skills(), preparo.experiencias()));

        AtomicBoolean houveRebaixamento = new AtomicBoolean(false);
        List<RequisitoClassificado> requisitosVerificados = resultadoClaude.requisitos().stream()
                .map(requisito -> verificar(requisito, preparo.skills(), preparo.experiencias(), owner,
                        preparo.vagaChave(), houveRebaixamento))
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
            logCausasRevisar(owner, preparo.vagaChave(), inconclusiva, houveRebaixamento.get(), frenteDivergente,
                    aderenciaPct);
        }

        return new Calculo(aderenciaPct, requisitosVerificados, gapsRiscos, decisao, revisar);
    }

    private AnaliseResultado gravar(Preparo preparo, Frente frente, String vagaUrl, boolean reanalisar,
            Calculo calculo) {
        Profile profile = preparo.profile();
        String vagaChave = preparo.vagaChave();

        // Reanálise sobrescreve a mesma linha (Spec F02, seção 6.3). Sem reanálise
        // sempre INSERT: quem arbitra uma corrida é a UNIQUE do banco, não um
        // "já existe?" que ficaria velho entre a etapa 1 e esta.
        MatchResult matchResult = reanalisar
                ? matchResultRepository.findByProfileAndVagaChave(profile, vagaChave)
                        .orElseGet(() -> new MatchResult(profile, frente, vagaChave, vagaUrl))
                : new MatchResult(profile, frente, vagaChave, vagaUrl);
        matchResult.aplicarAnalise(calculo.aderenciaPct(), calculo.requisitos(), calculo.gapsRiscos(),
                calculo.decisao(), calculo.revisar(), versaoPrompt, modelo, Instant.now());

        // saveAndFlush, não save: a violação da UNIQUE só é detectada no
        // INSERT/UPDATE real contra o banco. Com save() simples, o Hibernate
        // pode adiar a escrita até o commit — e o erro escaparia do
        // transactionTemplate.execute() sem passar pelo catch de quem chamou.
        matchResultRepository.saveAndFlush(matchResult);
        return concluir(matchResult, true);
    }

    /** O que a etapa 1 entrega para as etapas seguintes ({@code dedup} != null encerra o fluxo). */
    private record Preparo(Profile profile, String vagaChave, List<Skill> skills,
            List<ExperienciaProfissional> experiencias, AnaliseResultado dedup) {
    }

    /** Resultado da etapa 2 (Claude + verificação + cálculo), pronto para gravar. */
    private record Calculo(int aderenciaPct, List<RequisitoClassificado> requisitos, List<GapRisco> gapsRiscos,
            Decisao decisao, boolean revisar) {
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
