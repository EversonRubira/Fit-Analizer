package com.fitanalizer.match;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Skill;
import java.util.List;

/**
 * Insumo enviado à Claude API. {@code skills}/{@code experiencias} já devem
 * vir filtrados pela frente da vaga (+ TRANSVERSAL) — nunca o Profile
 * inteiro (Spec F02, seção 4). Quem monta este request é o
 * {@code MatchService} (ainda não implementado).
 */
public record FitAnalysisRequest(String textoVaga, List<Skill> skills, List<ExperienciaProfissional> experiencias) {
}
