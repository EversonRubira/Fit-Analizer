package com.fitanalizer.profile;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitanalizer.profile.dto.ExperienciaRequest;
import com.fitanalizer.profile.dto.ProfileCreateRequest;
import com.fitanalizer.profile.dto.SkillRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de fatia web (Controller): não repetem as regras de negócio já
 * cobertas em ProfileServiceTest — provam só a costura HTTP -> DTO -> Service
 * -> HTTP (status code, validação, serialização). Ver conversa no
 * STATUS.md/PR sobre teste orientado a risco: não é cobertura exaustiva de
 * todo endpoint, é prova de que a fiação funciona.
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProfileService service;

    @Test
    void criarDeveRetornar201ComPerfilCriado() throws Exception {
        Profile profile = new Profile("everson");
        profile.setBio("bio");
        when(service.criar(eq("everson"), eq("bio"))).thenReturn(profile);

        mockMvc.perform(post("/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileCreateRequest("everson", "bio"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value("everson"))
                .andExpect(jsonPath("$.bio").value("bio"));
    }

    @Test
    void criarDeveRetornar400QuandoOwnerAusente() throws Exception {
        mockMvc.perform(post("/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileCreateRequest("", "bio"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criarDeveRetornar409QuandoOwnerJaExiste() throws Exception {
        when(service.criar(anyString(), any())).thenThrow(new ProfileAlreadyExistsException("everson"));

        mockMvc.perform(post("/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileCreateRequest("everson", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("everson")));
    }

    @Test
    void buscarDeveRetornar404QuandoNaoExiste() throws Exception {
        when(service.buscar("desconhecido")).thenThrow(new ProfileNotFoundException("desconhecido"));

        mockMvc.perform(get("/profiles/desconhecido"))
                .andExpect(status().isNotFound());
    }

    @Test
    void buscarDeveRetornar200ComPerfil() throws Exception {
        Profile profile = new Profile("everson");
        profile.addSkill(new Skill("Java", 5, Frente.TECH));
        when(service.buscar("everson")).thenReturn(profile);

        mockMvc.perform(get("/profiles/everson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].nome").value("Java"))
                .andExpect(jsonPath("$.skills[0].frente").value("TECH"));
    }

    @Test
    void adicionarSkillDeveRetornar201() throws Exception {
        when(service.adicionarSkill(eq("everson"), eq("Java"), eq(5), eq(Frente.TECH)))
                .thenReturn(new Skill("Java", 5, Frente.TECH));

        mockMvc.perform(post("/profiles/everson/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SkillRequest("Java", 5, Frente.TECH))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Java"));
    }

    @Test
    void removerSkillDeveRetornar204() throws Exception {
        mockMvc.perform(delete("/profiles/everson/skills/Java"))
                .andExpect(status().isNoContent());
    }

    @Test
    void adicionarExperienciaDeveRetornar201() throws Exception {
        ExperienciaProfissional experiencia = new ExperienciaProfissional("Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null);
        when(service.adicionarExperiencia(eq("everson"), eq("Accenture"), eq("Analista"), eq(Frente.TECH),
                eq(LocalDate.of(2023, 1, 1)), isNull(), any())).thenReturn(experiencia);

        ExperienciaRequest request = new ExperienciaRequest("Accenture", "Analista", Frente.TECH,
                LocalDate.of(2023, 1, 1), null, null);

        mockMvc.perform(post("/profiles/everson/experiencias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empresa").value("Accenture"));
    }
}
