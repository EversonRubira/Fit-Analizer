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
