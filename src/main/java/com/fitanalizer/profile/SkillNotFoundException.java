package com.fitanalizer.profile;

public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String owner, String nome) {
        super("Skill '" + nome + "' não encontrada no Profile de owner: " + owner);
    }
}
