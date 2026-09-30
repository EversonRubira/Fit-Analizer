package com.fitanalizer.match;

/**
 * Retorno interno do {@code MatchService} pro {@code MatchController} —
 * carrega além do {@code MatchResult} se a análise foi nova/reanalisada
 * (201) ou devolvida do dedup (200, Spec F02 seção 5). Não é DTO de
 * resposta HTTP, por isso fica aqui e não em {@code match.dto}.
 */
record AnaliseResultado(MatchResult matchResult, boolean analiseNova) {
}
