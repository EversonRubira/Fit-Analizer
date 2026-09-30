package com.fitanalizer.match;

import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Profile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Resultado da análise de fit de uma vaga contra o Profile de um owner
 * (PRD/Spec F02). No máximo um por {@code (profile, vagaChave)} — garantido
 * pela UNIQUE abaixo, espelhando a checagem do Service antes de chamar a
 * Claude API (Spec F02, seção 7).
 */
@Entity
@Table(name = "match_results",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_results_profile_vaga",
                columnNames = { "profile_id", "vaga_chave" }))
public class MatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private Profile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "frente", nullable = false)
    private Frente frente;

    // vagaUrl quando informada, senão "hash:" + SHA-256 do texto normalizado (Spec F02, seção 2)
    @Column(name = "vaga_chave", nullable = false)
    private String vagaChave;

    @Column(name = "vaga_url")
    private String vagaUrl;

    @Column(name = "aderencia_pct")
    private Integer aderenciaPct;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "match_result_requisitos", joinColumns = @JoinColumn(name = "match_result_id"))
    @OrderColumn(name = "ordem")
    private List<RequisitoClassificado> requisitos = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "match_result_gaps_riscos", joinColumns = @JoinColumn(name = "match_result_id"))
    @OrderColumn(name = "ordem")
    private List<GapRisco> gapsRiscos = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "decisao")
    private Decisao decisao;

    @Column(name = "revisar", nullable = false)
    private boolean revisar;

    @Column(name = "versao_prompt")
    private String versaoPrompt;

    @Column(name = "modelo")
    private String modelo;

    @Column(name = "analisado_em")
    private Instant analisadoEm;

    protected MatchResult() {
    }

    /**
     * Cria a linha com as chaves de identidade (profile, frente, vagaChave,
     * vagaUrl) — os campos derivados da análise só são preenchidos depois,
     * via {@link #aplicarAnalise}, para não duplicar a mesma informação em
     * dois construtores diferentes (criação vs. reanálise usam o mesmo
     * caminho).
     */
    public MatchResult(Profile profile, Frente frente, String vagaChave, String vagaUrl) {
        this.profile = profile;
        this.frente = frente;
        this.vagaChave = vagaChave;
        this.vagaUrl = vagaUrl;
    }

    /**
     * Único ponto que altera os campos derivados de uma análise — usado
     * tanto na primeira análise quanto numa reanálise (mesma linha,
     * sobrescrita; Spec F02, seção 6.3). Mantém id, profile, frente e
     * vagaChave intocados.
     */
    public void aplicarAnalise(int aderenciaPct, List<RequisitoClassificado> requisitos,
            List<GapRisco> gapsRiscos, Decisao decisao, boolean revisar, String versaoPrompt,
            String modelo, Instant analisadoEm) {
        this.aderenciaPct = aderenciaPct;
        this.requisitos.clear();
        this.requisitos.addAll(requisitos);
        this.gapsRiscos.clear();
        this.gapsRiscos.addAll(gapsRiscos);
        this.decisao = decisao;
        this.revisar = revisar;
        this.versaoPrompt = versaoPrompt;
        this.modelo = modelo;
        this.analisadoEm = analisadoEm;
    }

    public Long getId() {
        return id;
    }

    public Profile getProfile() {
        return profile;
    }

    public Frente getFrente() {
        return frente;
    }

    public String getVagaChave() {
        return vagaChave;
    }

    public String getVagaUrl() {
        return vagaUrl;
    }

    public Integer getAderenciaPct() {
        return aderenciaPct;
    }

    public List<RequisitoClassificado> getRequisitos() {
        return requisitos;
    }

    public List<GapRisco> getGapsRiscos() {
        return gapsRiscos;
    }

    public Decisao getDecisao() {
        return decisao;
    }

    public boolean isRevisar() {
        return revisar;
    }

    public String getVersaoPrompt() {
        return versaoPrompt;
    }

    public String getModelo() {
        return modelo;
    }

    public Instant getAnalisadoEm() {
        return analisadoEm;
    }
}
