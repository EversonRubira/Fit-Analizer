package com.fitanalizer.match.dto;

import com.fitanalizer.profile.Frente;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo de {@code POST /matches} — mesmo contrato pro uso manual e pro
 * coletor externo (PRD F02, seção 6.1). {@code frente} aceita os 3 valores
 * do enum na deserialização; {@code TRANSVERSAL} é rejeitado no Service,
 * não aqui, porque {@code @NotNull} não distingue valores dentro do enum.
 */
public record MatchRequest(
        @NotBlank String owner,
        @NotBlank String textoVaga,
        @NotNull Frente frente,
        String vagaUrl,
        Boolean reanalisar) {
}
