package com.fitanalizer.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final ProfileRepository repository;

    public ProfileService(ProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Profile criar(String owner, String bio) {
        repository.findByOwner(owner).ifPresent(profile -> {
            throw new ProfileAlreadyExistsException(owner);
        });

        Profile profile = new Profile(owner);
        profile.setBio(bio);
        return repository.save(profile);
    }

    @Transactional(readOnly = true)
    public Profile buscar(String owner) {
        return buscarOuFalhar(owner);
    }

    @Transactional
    public Profile atualizar(String owner, String bio) {
        Profile profile = buscarOuFalhar(owner);
        if (bio != null) {
            profile.setBio(bio);
        }
        return repository.save(profile);
    }

    @Transactional
    public void excluir(String owner) {
        Profile profile = buscarOuFalhar(owner);
        // Delete via entidade carregada, não via query em massa: a cascata de
        // skills (@ElementCollection) e histórico (orphanRemoval) só roda
        // porque o Hibernate está gerenciando essa instância. Confirmado
        // contra o Postgres local (ver STATUS.md).
        repository.delete(profile);
    }

    @Transactional
    public Skill adicionarSkill(String owner, String nome, Integer anosExperiencia, Frente frente) {
        Profile profile = buscarOuFalhar(owner);

        // Dedup por nome, sem considerar frente: se a mesma skill parecer
        // pertencer às duas frentes, a modelagem correta é marcá-la
        // TRANSVERSAL, não duplicar o nome com frente diferente em cada uma.
        boolean jaExiste = profile.getSkills().stream().anyMatch(skill -> skill.temNome(nome));
        if (jaExiste) {
            throw new SkillAlreadyExistsException(owner, nome);
        }

        Skill novaSkill = new Skill(nome.trim(), anosExperiencia, frente);
        profile.addSkill(novaSkill);
        repository.save(profile);
        return novaSkill;
    }

    @Transactional
    public void removerSkill(String owner, String nome) {
        Profile profile = buscarOuFalhar(owner);

        Skill skillExistente = profile.getSkills().stream()
                .filter(skill -> skill.temNome(nome))
                .findFirst()
                .orElseThrow(() -> new SkillNotFoundException(owner, nome));

        profile.removeSkill(skillExistente);
        repository.save(profile);
    }

    private Profile buscarOuFalhar(String owner) {
        return repository.findByOwner(owner)
                .orElseThrow(() -> new ProfileNotFoundException(owner));
    }
}
