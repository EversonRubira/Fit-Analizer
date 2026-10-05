package com.fitanalizer.match;

import com.fitanalizer.profile.Skill;
import java.util.List;

/**
 * Confere em código, fora do LLM, o mínimo de anos que a vaga exige de uma
 * tecnologia (prompt v3). A Claude só extrai {@code tecnologia} e
 * {@code anosMinimos}; quem compara com {@link Skill#getAnosExperiencia()} é
 * esta classe — "3+ anos de Java" com 1 ano no Profile não pode ficar
 * PARCIAL só porque a Claude achou razoável.
 *
 * <p>Mesmo contrato do {@link VerificadorEvidencia}: não loga, devolve o
 * próprio requisito ou a cópia rebaixada; quem chama compara as
 * classificações e decide (o {@link MatchService} loga e marca
 * {@code revisar}). Usada também pelo {@code RegressaoPromptTest}.
 */
public final class VerificadorAnosMinimos {

    private VerificadorAnosMinimos() {
    }

    /**
     * Sem {@code anosMinimos}: nada a verificar. Com ele, localiza a skill
     * pelo nome ({@link Skill#temNome}: ignora maiúsculas e espaços nas pontas,
     * sem substring) e rebaixa para NENHUM se a skill não existir ou tiver
     * menos anos que o mínimo — mesmo que a Claude tenha dito FORTE ou PARCIAL.
     *
     * <p>{@code skills} já deve vir filtrado pela frente da vaga (+ TRANSVERSAL).
     */
    public static RequisitoClassificado verificar(RequisitoClassificado requisito, List<Skill> skills) {
        if (requisito.getAnosMinimos() == null || requisito.getClassificacao() == Classificacao.NENHUM) {
            return requisito;
        }
        boolean atende = skills.stream()
                .filter(skill -> skill.temNome(requisito.getTecnologia()))
                .anyMatch(skill -> skill.getAnosExperiencia() != null
                        && skill.getAnosExperiencia() >= requisito.getAnosMinimos());
        return atende ? requisito : requisito.rebaixarParaNenhum();
    }
}
