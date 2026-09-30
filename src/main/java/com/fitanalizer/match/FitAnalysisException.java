package com.fitanalizer.match;

/**
 * Falha ao obter uma análise da Claude API. Tratada no Controller como
 * 502 — erro de dependência externa, não do próprio serviço (Spec F02,
 * seção 3.3). Nada é persistido quando isso acontece; sem retry automático
 * em v1.
 */
public class FitAnalysisException extends RuntimeException {

    public FitAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }

    public FitAnalysisException(String message) {
        super(message);
    }
}
