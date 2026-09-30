package com.fitanalizer.match;

import java.util.List;

/**
 * Cálculo 100% determinístico da aderência (PRD/Spec F02, seção 6) — roda
 * sobre requisitos já verificados (após rebaixamento por evidência
 * inválida), nunca sobre a resposta crua da Claude.
 */
public final class AderenciaCalculadora {

    private AderenciaCalculadora() {
    }

    /**
     * {@code (soma dos pesos dos obrigatórios / total de obrigatórios) x 100},
     * com forte=1.0, parcial=0.5, nenhum=0, sempre arredondando para baixo.
     * Lista vazia (vaga sem requisitos obrigatórios) devolve 0 — caminho da
     * seção 6.7 do PRD, tratado no Service, não aqui.
     */
    public static int aderenciaPct(List<RequisitoClassificado> requisitos) {
        if (requisitos == null || requisitos.isEmpty()) {
            return 0;
        }
        double somaPesos = requisitos.stream()
                .mapToDouble(AderenciaCalculadora::peso)
                .sum();
        return (int) Math.floor(somaPesos / requisitos.size() * 100);
    }

    private static double peso(RequisitoClassificado requisito) {
        return switch (requisito.getClassificacao()) {
            case FORTE -> 1.0;
            case PARCIAL -> 0.5;
            case NENHUM -> 0.0;
        };
    }

    /**
     * Zonas de fronteira entre faixas de decisão (PRD/Spec F02, seção 6.6):
     * 28-32, 48-52, 68-72, 83-87 — uma das condições que marca
     * {@code revisar = true}.
     */
    public static boolean zonaDeFronteira(int aderenciaPct) {
        return estaEntre(aderenciaPct, 28, 32)
                || estaEntre(aderenciaPct, 48, 52)
                || estaEntre(aderenciaPct, 68, 72)
                || estaEntre(aderenciaPct, 83, 87);
    }

    private static boolean estaEntre(int valor, int minInclusive, int maxInclusive) {
        return valor >= minInclusive && valor <= maxInclusive;
    }
}
