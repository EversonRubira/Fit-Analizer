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

    protected RequisitoClassificado() {
    }

    public RequisitoClassificado(String descricao, Classificacao classificacao, String evidenciaRef) {
        this.descricao = descricao;
        this.classificacao = classificacao;
        this.evidenciaRef = evidenciaRef;
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

    /**
     * Rebaixamento por alucinação (evidência citada que não existe no
     * Profile filtrado pela frente) ou por classificação forte/parcial sem
     * evidência nenhuma — mesmo tratamento para os dois casos (Spec F02,
     * seção 3.1).
     */
    public RequisitoClassificado rebaixarParaNenhum() {
        return new RequisitoClassificado(descricao, Classificacao.NENHUM, null);
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
                && Objects.equals(evidenciaRef, that.evidenciaRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(descricao, classificacao, evidenciaRef);
    }
}
