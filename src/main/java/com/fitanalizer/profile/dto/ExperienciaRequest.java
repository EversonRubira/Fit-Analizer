package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.Frente;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Corpo de entrada do POST /profiles/{owner}/experiencias. `dataFim` é
 * opcional por natureza (null = emprego atual), por isso não tem @NotNull.
 */
public record ExperienciaRequest(
        @NotBlank String empresa,
        @NotBlank String cargo,
        @NotNull Frente frente,
        @NotNull LocalDate dataInicio,
        LocalDate dataFim,
        List<String> tecnologiasUsadas) {
}
