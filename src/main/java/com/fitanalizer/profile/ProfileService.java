package com.fitanalizer.profile;

import java.time.LocalDate;
import java.util.List;
import org.hibernate.Hibernate;
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

    @Transactional
    public ExperienciaProfissional adicionarExperiencia(String owner, String empresa, String cargo, Frente frente,
            LocalDate dataInicio, LocalDate dataFim, List<String> tecnologiasUsadas) {
        Profile profile = buscarOuFalhar(owner);

        ExperienciaProfissional experiencia = new ExperienciaProfissional(empresa, cargo, frente, dataInicio,
                dataFim);
        if (tecnologiasUsadas != null) {
            experiencia.setTecnologiasUsadas(tecnologiasUsadas);
        }

        profile.addExperienciaProfissional(experiencia);
        repository.save(profile);
        return experiencia;
    }

    @Transactional
    public ExperienciaProfissional atualizarExperiencia(String owner, Long experienciaId, String empresa,
            String cargo, Frente frente, LocalDate dataInicio, LocalDate dataFim, List<String> tecnologiasUsadas) {
        Profile profile = buscarOuFalhar(owner);
        ExperienciaProfissional experiencia = buscarExperienciaOuFalhar(profile, owner, experienciaId);

        // Mesma semântica de PATCH do bio: null = não altera. Exceção:
        // dataFim=null já é um estado válido de domínio (emprego atual), então
        // não dá pra reabrir um emprego encerrado via PATCH (limitação
        // conhecida, documentada no STATUS.md — não há caso de uso real hoje
        // para reverter dataFim de volta a null).
        if (empresa != null) {
            experiencia.setEmpresa(empresa);
        }
        if (cargo != null) {
            experiencia.setCargo(cargo);
        }
        if (frente != null) {
            experiencia.setFrente(frente);
        }
        if (dataInicio != null) {
            experiencia.setDataInicio(dataInicio);
        }
        if (dataFim != null) {
            experiencia.setDataFim(dataFim);
        }
        if (tecnologiasUsadas != null) {
            experiencia.setTecnologiasUsadas(tecnologiasUsadas);
        }

        repository.save(profile);
        return experiencia;
    }

    @Transactional
    public void removerExperiencia(String owner, Long experienciaId) {
        Profile profile = buscarOuFalhar(owner);
        ExperienciaProfissional experiencia = buscarExperienciaOuFalhar(profile, owner, experienciaId);

        profile.removeExperienciaProfissional(experiencia);
        repository.save(profile);
    }

    private ExperienciaProfissional buscarExperienciaOuFalhar(Profile profile, String owner, Long experienciaId) {
        return profile.getHistoricoProfissional().stream()
                .filter(experiencia -> experiencia.getId().equals(experienciaId))
                .findFirst()
                .orElseThrow(() -> new ExperienciaNotFoundException(owner, experienciaId));
    }

    private Profile buscarOuFalhar(String owner) {
        Profile profile = repository.findByOwner(owner)
                .orElseThrow(() -> new ProfileNotFoundException(owner));
        // Força o carregamento das coleções LAZY ainda dentro da transação.
        // Necessário porque o Controller (Bloco 3) monta o DTO de resposta
        // depois que este método retorna, já fora da transação — com
        // open-in-view: false, acessar uma coleção LAZY nesse ponto lançaria
        // LazyInitializationException. Duas camadas: skills e o historico em
        // si, MAS TAMBÉM tecnologiasUsadas dentro de cada
        // ExperienciaProfissional — é @ElementCollection LAZY um nível mais
        // fundo, fácil de esquecer porque não aparece só olhando pra Profile.
        Hibernate.initialize(profile.getSkills());
        Hibernate.initialize(profile.getHistoricoProfissional());
        profile.getHistoricoProfissional().forEach(experiencia -> Hibernate.initialize(
                experiencia.getTecnologiasUsadas()));
        return profile;
    }
}
