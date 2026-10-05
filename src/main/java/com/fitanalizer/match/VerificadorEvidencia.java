package com.fitanalizer.match;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Skill;
import java.util.List;
import java.util.Optional;

/**
 * Confere se uma {@code evidencia_ref} citada pela Claude aponta para algo
 * que existe de fato no subconjunto do Profile já filtrado pela frente da
 * vaga (Spec F02, seção 3.1 e 4). Não compara texto livre: o prompt
 * instrui a Claude a citar sempre um token exato ({@code skill:<nome>} ou
 * {@code exp:<id>}), então esta classe só verifica pertencimento a um
 * conjunto conhecido — nada de fuzzy match ou normalização própria além da
 * já usada em {@link Skill#temNome}.
 */
public final class VerificadorEvidencia {

    private static final String PREFIXO_SKILL = "skill:";
    private static final String PREFIXO_EXPERIENCIA = "exp:";

    private VerificadorEvidencia() {
    }

    /**
     * Regra de rebaixamento (Spec F02, seção 3.1): requisito {@code forte} ou
     * {@code parcial} cuja evidência não existe no perfil vira {@code nenhum}.
     * Devolve o próprio requisito quando está ok (com a referência
     * normalizada, ver {@link #normalizar}), ou a cópia rebaixada. Não
     * loga: quem chama compara as classificações para saber se houve
     * rebaixamento e decide o que fazer (o {@link MatchService} loga e marca
     * {@code revisar}). Única fonte da regra — usada também pelo
     * {@code RegressaoPromptTest}.
     */
    public static RequisitoClassificado verificar(RequisitoClassificado requisito, List<Skill> skills,
            List<ExperienciaProfissional> experiencias) {
        if (requisito.getClassificacao() == Classificacao.NENHUM) {
            return requisito;
        }
        if (!referenciaValida(requisito.getEvidenciaRef(), skills, experiencias)) {
            return requisito.rebaixarParaNenhum();
        }
        // Grava o token sem colchetes: é o formato que o resto do sistema espera.
        String normalizada = normalizar(requisito.getEvidenciaRef());
        return normalizada.equals(requisito.getEvidenciaRef()) ? requisito
                : requisito.comEvidencia(normalizada);
    }

    /**
     * {@code skills} e {@code experiencias} já devem vir filtrados pela
     * frente da vaga (+ TRANSVERSAL) — esta classe não aplica esse filtro,
     * só verifica pertencimento ao que recebeu.
     */
    public static boolean referenciaValida(String evidenciaRef, List<Skill> skills,
            List<ExperienciaProfissional> experiencias) {
        if (evidenciaRef == null || evidenciaRef.isBlank()) {
            return false;
        }
        evidenciaRef = normalizar(evidenciaRef);
        if (evidenciaRef.startsWith(PREFIXO_SKILL)) {
            String nome = evidenciaRef.substring(PREFIXO_SKILL.length());
            return skills.stream().anyMatch(skill -> skill.temNome(nome));
        }
        if (evidenciaRef.startsWith(PREFIXO_EXPERIENCIA)) {
            return idExperiencia(evidenciaRef)
                    .map(id -> experiencias.stream().anyMatch(exp -> id.equals(exp.getId())))
                    .orElse(false);
        }
        return false;
    }

    /**
     * O prompt lista o perfil como {@code [skill:Java]} / {@code [exp:2]}, e a
     * Claude às vezes devolve o token com os colchetes. Aceita só isso: trim e
     * UM par de colchetes externos. Prefixo e existência continuam estritos.
     */
    static String normalizar(String evidenciaRef) {
        String ref = evidenciaRef.trim();
        if (ref.length() >= 2 && ref.startsWith("[") && ref.endsWith("]")) {
            return ref.substring(1, ref.length() - 1);
        }
        return ref;
    }

    private static Optional<Long> idExperiencia(String evidenciaRef) {
        try {
            return Optional.of(Long.valueOf(evidenciaRef.substring(PREFIXO_EXPERIENCIA.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
