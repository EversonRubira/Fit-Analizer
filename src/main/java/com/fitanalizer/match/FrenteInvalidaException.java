package com.fitanalizer.match;

import com.fitanalizer.profile.Frente;

/**
 * {@code frente = TRANSVERSAL} informado no contrato de entrada — valor
 * válido em skill/experiência, mas não como classificação de uma vaga
 * (PRD F02, seção 6.1).
 */
public class FrenteInvalidaException extends RuntimeException {

    public FrenteInvalidaException(Frente frente) {
        super("Frente inválida para análise de vaga: " + frente + " (esperado COMEX ou TECH)");
    }
}
