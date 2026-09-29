package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.Frente;
import java.time.LocalDate;
import java.util.List;

/**
 * Corpo de entrada do PATCH /profiles/{owner}/experiencias/{id}. Todo campo
 * null significa "não alterar" — mesma semântica do ProfileUpdateRequest.
 *
 * Limitação conhecida: como `dataFim == null` também é um estado de domínio
 * válido (emprego atual), não há como usar este PATCH para reabrir um
 * emprego já encerrado (voltar `dataFim` a null). Não implementado de
 * propósito — sem caso de uso real hoje; documentado no STATUS.md.
 */
public record ExperienciaUpdateRequest(
        String empresa,
        String cargo,
        Frente frente,
        LocalDate dataInicio,
        LocalDate dataFim,
        List<String> tecnologiasUsadas) {
}
