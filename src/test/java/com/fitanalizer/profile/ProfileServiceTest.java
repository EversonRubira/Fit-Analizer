package com.fitanalizer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private ProfileRepository repository;

    private ProfileService service;

    @BeforeEach
    void setUp() {
        service = new ProfileService(repository);
    }

    @Test
    void criarDeveFalharQuandoOwnerJaExiste() {
        Profile existente = new Profile("everson");
        when(repository.findByOwner("everson")).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.criar("everson", "bio"))
                .isInstanceOf(ProfileAlreadyExistsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void buscarDeveFalharQuandoOwnerNaoExiste() {
        when(repository.findByOwner("desconhecido")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buscar("desconhecido"))
                .isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void adicionarSkillDeveFalharQuandoNomeJaExisteIgnorandoCaixa() {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Java", 5, Frente.TECH));
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.adicionarSkill("everson", "java", 2, Frente.TECH))
                .isInstanceOf(SkillAlreadyExistsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void adicionarSkillDeveAceitarNomesDiferentesMesmoRelacionados() {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Java", 5, Frente.TECH));
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        service.adicionarSkill("everson", "Arquitetura em Java", 1, Frente.TECH);

        assertThat(profile.getSkills()).hasSize(2);
    }

    @Test
    void adicionarSkillDeveFalharQuandoNomeJaExisteMesmoEmFrenteDiferente() {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Negociação", 5, Frente.COMEX));
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.adicionarSkill("everson", "Negociação", 3, Frente.TECH))
                .isInstanceOf(SkillAlreadyExistsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void removerSkillDeveFalharQuandoNaoExiste() {
        Profile profile = new Profile("everson");
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.removerSkill("everson", "Java"))
                .isInstanceOf(SkillNotFoundException.class);
    }

    @Test
    void removerSkillDeveRemoverIgnorandoCaixaEEspacos() {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Java", 5, Frente.TECH));
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        service.removerSkill("everson", " java ");

        assertThat(profile.getSkills()).isEmpty();
    }
}
