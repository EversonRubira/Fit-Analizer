package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Skill;

/** Resposta de skill — nunca a entidade `Skill` (embeddable) direto. */
public record SkillResponse(String nome, Integer anosExperiencia, Frente frente) {

    public static SkillResponse de(Skill skill) {
        return new SkillResponse(skill.getNome(), skill.getAnosExperiencia(), skill.getFrente());
    }
}
