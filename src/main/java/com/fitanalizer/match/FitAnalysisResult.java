package com.fitanalizer.match;

import com.fitanalizer.profile.Frente;
import java.util.List;

/**
 * Saída crua da Claude API — ainda **sem** verificação de evidência nem
 * cálculo de aderência (isso é responsabilidade do {@code MatchService},
 * não do client). {@code requisitos} pode vir vazio (vaga sem obrigatórios
 * / texto que não é vaga — PRD F02, seção 6.7).
 */
public record FitAnalysisResult(Frente frenteDetectada, List<RequisitoClassificado> requisitos,
        List<GapRisco> gapsRiscos) {
}
