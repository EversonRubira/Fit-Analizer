package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.Frente;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Corpo de entrada do POST /profiles/{owner}/skills. */
public record SkillRequest(
        @NotBlank String nome,
        @NotNull @PositiveOrZero Integer anosExperiencia,
        @NotNull Frente frente) {
}
