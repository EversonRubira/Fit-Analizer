package com.fitanalizer.profile;

public class ExperienciaNotFoundException extends RuntimeException {

    public ExperienciaNotFoundException(String owner, Long experienciaId) {
        super("Experiência " + experienciaId + " não encontrada no Profile de owner: " + owner);
    }
}
