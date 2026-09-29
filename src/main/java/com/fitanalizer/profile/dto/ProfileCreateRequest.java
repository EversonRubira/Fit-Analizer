package com.fitanalizer.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de entrada do POST /profiles. `owner` vai aqui, não no path, porque
 * ainda não existe um recurso identificável na URL nesse momento — é
 * justamente o dado que está sendo usado para criar o recurso.
 */
public record ProfileCreateRequest(
        @NotBlank String owner,
        @Size(max = 2000) String bio) {
}
