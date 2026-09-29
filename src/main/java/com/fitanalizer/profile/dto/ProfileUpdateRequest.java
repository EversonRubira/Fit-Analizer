package com.fitanalizer.profile.dto;

import jakarta.validation.constraints.Size;

/**
 * Corpo de entrada do PATCH /profiles/{owner}. `bio == null` significa "não
 * alterar"; só sobrescreve quando o campo vem preenchido (Spec F01, seção
 * 1.4). Sem @NotBlank de propósito — o próprio ponto do PATCH é permitir
 * omitir o campo.
 */
public record ProfileUpdateRequest(
        @Size(max = 2000) String bio) {
}
