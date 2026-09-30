package com.fitanalizer.match;

/**
 * Decisão de candidatura por faixa de {@code aderenciaPct} (PRD F02, seção
 * 6.4). Puramente derivada do percentual — sem julgamento de LLM.
 */
public enum Decisao {
    CV_PRIORITARIO,
    CV_CARTA,
    CV_CARTA_COM_AVISO,
    NAO_CANDIDATAR,
    FORA_ESCOPO;

    /**
     * Faixas: 85-100 cv_prioritario, 70-84 cv_carta, 50-69
     * cv_carta_com_aviso, 30-49 nao_candidatar, 0-29 fora_escopo.
     */
    public static Decisao paraPct(int aderenciaPct) {
        if (aderenciaPct >= 85) {
            return CV_PRIORITARIO;
        }
        if (aderenciaPct >= 70) {
            return CV_CARTA;
        }
        if (aderenciaPct >= 50) {
            return CV_CARTA_COM_AVISO;
        }
        if (aderenciaPct >= 30) {
            return NAO_CANDIDATAR;
        }
        return FORA_ESCOPO;
    }
}
