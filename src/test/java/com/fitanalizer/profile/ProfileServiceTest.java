package com.fitanalizer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

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

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void criarDeveTraduzirViolacaoDaUniqueEmProfileAlreadyExists() {
        // Por quê: é o caminho da corrida — findByOwner não viu ninguém, mas outra
        // requisição gravou antes; a UNIQUE recusa e o cliente deve ver 409, não 500.
        when(repository.findByOwner("everson")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_profiles_owner"));

        assertThatThrownBy(() -> service.criar("everson", "bio"))
                .isInstanceOf(ProfileAlreadyExistsException.class);
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

    @Test
    void adicionarExperienciaDeveIncluirNoHistorico() {
        Profile profile = new Profile("everson");
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        service.adicionarExperiencia("everson", "Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null, List.of("Java", "Spring"));

        assertThat(profile.getHistoricoProfissional()).hasSize(1);
        assertThat(profile.getHistoricoProfissional().get(0).getEmpresa()).isEqualTo("Accenture");
    }

    @Test
    void atualizarExperienciaDeveFalharQuandoNaoExiste() {
        Profile profile = new Profile("everson");
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.atualizarExperiencia("everson", 99L, null, null, null, null, null, null))
                .isInstanceOf(ExperienciaNotFoundException.class);
    }

    @Test
    void atualizarExperienciaDeveAlterarSomenteCamposInformados() {
        Profile profile = new Profile("everson");
        ExperienciaProfissional experiencia = new ExperienciaProfissional("Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null);
        ReflectionTestUtils.setField(experiencia, "id", 1L);
        profile.addExperienciaProfissional(experiencia);
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        // Só encerra o emprego (dataFim); empresa/cargo/frente/dataInicio ficam como estavam.
        service.atualizarExperiencia("everson", 1L, null, null, null, null, LocalDate.of(2026, 9, 1), null);

        assertThat(experiencia.getEmpresa()).isEqualTo("Accenture");
        assertThat(experiencia.getDataFim()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void removerExperienciaDeveFalharQuandoNaoExiste() {
        Profile profile = new Profile("everson");
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.removerExperiencia("everson", 1L))
                .isInstanceOf(ExperienciaNotFoundException.class);
    }

    @Test
    void removerExperienciaDeveRemoverDoHistorico() {
        Profile profile = new Profile("everson");
        ExperienciaProfissional experiencia = new ExperienciaProfissional("Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null);
        ReflectionTestUtils.setField(experiencia, "id", 1L);
        profile.addExperienciaProfissional(experiencia);
        when(repository.findByOwner("everson")).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        service.removerExperiencia("everson", 1L);

        assertThat(profile.getHistoricoProfissional()).isEmpty();
    }
}
