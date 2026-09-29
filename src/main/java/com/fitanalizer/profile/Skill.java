package com.fitanalizer.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

@Embeddable
public class Skill {

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "anos_experiencia", nullable = false)
    private Integer anosExperiencia;

    @Enumerated(EnumType.STRING)
    @Column(name = "frente", nullable = false)
    private Frente frente;

    protected Skill() {
    }

    public Skill(String nome, Integer anosExperiencia, Frente frente) {
        this.nome = nome;
        this.anosExperiencia = anosExperiencia;
        this.frente = frente;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Integer getAnosExperiencia() {
        return anosExperiencia;
    }

    public void setAnosExperiencia(Integer anosExperiencia) {
        this.anosExperiencia = anosExperiencia;
    }

    public Frente getFrente() {
        return frente;
    }

    public void setFrente(Frente frente) {
        this.frente = frente;
    }

    /**
     * Compara o nome ignorando maiúsculas/minúsculas e espaços nas pontas.
     * Não faz busca por substring nem relação semântica: "Java" e "java" são
     * a mesma skill; "Arquitetura em Java" não é.
     */
    public boolean temNome(String outroNome) {
        return outroNome != null && nome.trim().equalsIgnoreCase(outroNome.trim());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Skill skill)) {
            return false;
        }
        return Objects.equals(nome, skill.nome)
                && Objects.equals(anosExperiencia, skill.anosExperiencia)
                && frente == skill.frente;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nome, anosExperiencia, frente);
    }
}
