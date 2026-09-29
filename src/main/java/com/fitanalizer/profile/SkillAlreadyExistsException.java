package com.fitanalizer.profile;

public class SkillAlreadyExistsException extends RuntimeException {

    public SkillAlreadyExistsException(String owner, String nome) {
        super("Skill '" + nome + "' já existe no Profile de owner: " + owner);
    }
}
