package com.fitanalizer.match.dto;

import com.fitanalizer.match.Decisao;
import com.fitanalizer.match.MatchResult;
import com.fitanalizer.profile.Frente;
import java.time.Instant;
import java.util.List;

/**
 * {@code versaoPrompt}/{@code modelo} entram na resposta — diferente do
 * reflexo inicial de tratar como "campo interno" (Spec F02, seção 5) —
 * porque servem pra comparar resultados durante a fase de teste manual com
 * crédito real. {@code vagaChave} não entra: é detalhe de implementação do
 * dedup, sem valor pra quem lê a resposta.
 */
public record MatchResponse(
        Long id,
        Frente frente,
        String vagaUrl,
        Integer aderenciaPct,
        List<RequisitoResponse> requisitos,
        List<GapRiscoResponse> gapsRiscos,
        Decisao decisao,
        boolean revisar,
        String versaoPrompt,
        String modelo,
        Instant analisadoEm) {

    public static MatchResponse de(MatchResult matchResult) {
        return new MatchResponse(
                matchResult.getId(),
                matchResult.getFrente(),
                matchResult.getVagaUrl(),
                matchResult.getAderenciaPct(),
                matchResult.getRequisitos().stream().map(RequisitoResponse::de).toList(),
                matchResult.getGapsRiscos().stream().map(GapRiscoResponse::de).toList(),
                matchResult.getDecisao(),
                matchResult.isRevisar(),
                matchResult.getVersaoPrompt(),
                matchResult.getModelo(),
                matchResult.getAnalisadoEm());
    }
}
