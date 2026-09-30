package com.fitanalizer.match;

/**
 * Fronteira com o provedor de LLM (Spec F02, seção 1.1). Isolada numa
 * interface não porque exista mais de um provedor hoje — decidiu-se manter
 * só a Claude API, ver {@code STATUS.md} — mas porque é uma dependência
 * externa real (rede, custo, formato de resposta) que não deve vazar pro
 * Service como código de HTTP/SDK solto.
 */
public interface FitAnalysisClient {

    /**
     * @throws FitAnalysisException em qualquer falha (timeout, erro HTTP,
     *         resposta fora do schema esperado) — Spec F02, seção 3.3.
     */
    FitAnalysisResult analisar(FitAnalysisRequest request);
}
