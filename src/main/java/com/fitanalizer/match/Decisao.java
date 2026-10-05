package com.fitanalizer.match;

import java.util.List;

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

    /**
     * Teto por requisito eliminatório não atendido — ÚNICO lugar desta regra.
     * Se algum requisito com {@code anosMinimos} ficou NENHUM (depois das
     * verificações em código), a decisão não passa de {@link #NAO_CANDIDATAR}:
     * o percentual sozinho deixaria "3+ anos de Java" pesar igual a "Git".
     *
     * <p>Só requisito {@code eliminatorio} com {@code anosMinimos} aciona o
     * teto, por ser o único eliminatório comparável com o Profile. Exigir
     * {@code eliminatorio} (desde o prompt v4) impede que um {@code anosMinimos}
     * espúrio, copiado do Profile num requisito comum, derrube a vaga. Formação, idioma e disponibilidade não são
     * eliminatórios ({@code foraDoPerfil}); senioridade sem número de anos
     * fica {@code eliminatorio} mas sem teto. Nunca melhora a decisão:
     * {@link #FORA_ESCOPO} continua {@link #FORA_ESCOPO}.
     */
    public static Decisao aplicarTeto(Decisao decisao, List<RequisitoClassificado> requisitosVerificados) {
        // Lista explícita, não ordinal(): reordenar o enum não pode mudar a regra.
        boolean acimaDoTeto = decisao == CV_PRIORITARIO || decisao == CV_CARTA || decisao == CV_CARTA_COM_AVISO;
        return acimaDoTeto && temEliminatorioNaoAtendido(requisitosVerificados) ? NAO_CANDIDATAR : decisao;
    }

    static boolean temEliminatorioNaoAtendido(List<RequisitoClassificado> requisitosVerificados) {
        return requisitosVerificados.stream()
                .anyMatch(req -> req.isEliminatorio() && req.getAnosMinimos() != null
                        && req.getClassificacao() == Classificacao.NENHUM);
    }
}
