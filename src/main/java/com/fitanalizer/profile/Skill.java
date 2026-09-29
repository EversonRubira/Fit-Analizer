package com.fitanalizer.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

@Embeddable
public class Skill {

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "anos_experiencia", nullable = false)
    private Integer anosExperiencia;

    protected Skill() {
    }

    public Skill(String nome, Integer anosExperiencia) {
        this.nome = nome;
        this.anosExperiencia = anosExperiencia;
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
        return Objects.equals(nome, skill.nome) && Objects.equals(anosExperiencia, skill.anosExperiencia);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nome, anosExperiencia);
    }
}
