package com.fitanalizer.match;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

/**
 * Um requisito obrigatório da vaga, com a classificação da Claude e a
 * referência de evidência (Spec F02, seção 3.1). Imutável de propósito: o
 * único jeito de mudar a classificação é {@link #rebaixarParaNenhum()},
 * que devolve uma instância nova — evita mutação parcial inconsistente
 * (ex: classificação NENHUM com evidência ainda presente).
 *
 * <p>Campos de julgamento (prompt v3), todos opcionais e anuláveis no banco
 * (linhas gravadas antes da v3 ficam com null):
 * <ul>
 * <li>{@code eliminatorio}: a vaga exige mínimo de anos de uma tecnologia ou
 * senioridade explícita.</li>
 * <li>{@code tecnologia} + {@code anosMinimos}: o mínimo de anos, comparado em
 * código com a skill do Profile ({@link VerificadorAnosMinimos}). Só requisito
 * com {@code anosMinimos} pode limitar a decisão ({@link Decisao#aplicarTeto}).</li>
 * <li>{@code foraDoPerfil}: requisito que o Profile não tem como comprovar
 * (formação, nível de idioma, disponibilidade/localização). Não é
 * eliminatório; se ficar NENHUM, só liga {@code revisar}.</li>
 * </ul>
 */
@Embeddable
public class RequisitoClassificado {

    @Column(name = "descricao", nullable = false)
    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(name = "classificacao", nullable = false)
    private Classificacao classificacao;

    // null quando a Claude não citou evidência (esperado quando classificacao = NENHUM)
    @Column(name = "evidencia_ref")
    private String evidenciaRef;

    // Wrappers (não primitivos): linhas anteriores ao prompt v3 têm null aqui.
    @Column(name = "eliminatorio")
    private Boolean eliminatorio;

    @Column(name = "tecnologia")
    private String tecnologia;

    @Column(name = "anos_minimos")
    private Integer anosMinimos;

    @Column(name = "fora_do_perfil")
    private Boolean foraDoPerfil;

    protected RequisitoClassificado() {
    }

    public RequisitoClassificado(String descricao, Classificacao classificacao, String evidenciaRef) {
        this(descricao, classificacao, evidenciaRef, false, null, null, false);
    }

    public RequisitoClassificado(String descricao, Classificacao classificacao, String evidenciaRef,
            boolean eliminatorio, String tecnologia, Integer anosMinimos, boolean foraDoPerfil) {
        this.descricao = descricao;
        this.classificacao = classificacao;
        this.evidenciaRef = evidenciaRef;
        this.eliminatorio = eliminatorio;
        this.tecnologia = tecnologia;
        this.anosMinimos = anosMinimos;
        this.foraDoPerfil = foraDoPerfil;
    }

    public String getDescricao() {
        return descricao;
    }

    public Classificacao getClassificacao() {
        return classificacao;
    }

    public String getEvidenciaRef() {
        return evidenciaRef;
    }

    public boolean isEliminatorio() {
        return Boolean.TRUE.equals(eliminatorio);
    }

    public String getTecnologia() {
        return tecnologia;
    }

    public Integer getAnosMinimos() {
        return anosMinimos;
    }

    public boolean isForaDoPerfil() {
        return Boolean.TRUE.equals(foraDoPerfil);
    }

    /** Cópia com outra evidência, mantendo classificação e campos de julgamento. */
    public RequisitoClassificado comEvidencia(String novaEvidenciaRef) {
        return new RequisitoClassificado(descricao, classificacao, novaEvidenciaRef, isEliminatorio(), tecnologia,
                anosMinimos, isForaDoPerfil());
    }

    /**
     * Rebaixamento por alucinação (evidência citada que não existe no
     * Profile filtrado pela frente), por classificação forte/parcial sem
     * evidência nenhuma (Spec F02, seção 3.1) ou por anos abaixo do mínimo
     * ({@link VerificadorAnosMinimos}). Mantém os campos de julgamento: o teto
     * da decisão depende deles depois do rebaixamento.
     */
    public RequisitoClassificado rebaixarParaNenhum() {
        return new RequisitoClassificado(descricao, Classificacao.NENHUM, null, isEliminatorio(), tecnologia,
                anosMinimos, isForaDoPerfil());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RequisitoClassificado that)) {
            return false;
        }
        return Objects.equals(descricao, that.descricao)
                && classificacao == that.classificacao
                && Objects.equals(evidenciaRef, that.evidenciaRef)
                && isEliminatorio() == that.isEliminatorio()
                && Objects.equals(tecnologia, that.tecnologia)
                && Objects.equals(anosMinimos, that.anosMinimos)
                && isForaDoPerfil() == that.isForaDoPerfil();
    }

    @Override
    public int hashCode() {
        return Objects.hash(descricao, classificacao, evidenciaRef, isEliminatorio(), tecnologia, anosMinimos,
                isForaDoPerfil());
    }
}
