package com.fitanalizer.match;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitanalizer.match.dto.MatchRequest;
import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Profile;
import com.fitanalizer.profile.ProfileNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste de fatia web — mesmo racional do ProfileControllerTest: só a
 * costura HTTP -> DTO -> Service -> HTTP, não repete as regras de negócio
 * (já cobertas em MatchServiceTest).
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(MatchController.class)
class MatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MatchService service;

    private MatchResult matchResultDeExemplo() {
        Profile profile = new Profile("everson");
        MatchResult matchResult = new MatchResult(profile, Frente.TECH, "https://vaga.example/1",
                "https://vaga.example/1");
        matchResult.aplicarAnalise(100, List.of(), List.of(), Decisao.CV_PRIORITARIO, false, "v1",
                "claude-haiku-4-5", Instant.now());
        return matchResult;
    }

    @Test
    void analiseNovaDeveRetornar201() throws Exception {
        when(service.analisar(eq("everson"), anyString(), eq(Frente.TECH), any(), anyBoolean()))
                .thenReturn(new AnaliseResultado(matchResultDeExemplo(), true));

        MatchRequest request = new MatchRequest("everson", "texto da vaga", Frente.TECH, "https://vaga.example/1",
                null);

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisao").value("CV_PRIORITARIO"));
    }

    @Test
    void dedupDeveRetornar200() throws Exception {
        when(service.analisar(eq("everson"), anyString(), eq(Frente.TECH), any(), anyBoolean()))
                .thenReturn(new AnaliseResultado(matchResultDeExemplo(), false));

        MatchRequest request = new MatchRequest("everson", "texto da vaga", Frente.TECH, "https://vaga.example/1",
                null);

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void frenteAusenteDeveRetornar400() throws Exception {
        String jsonSemFrente = """
                {"owner":"everson","textoVaga":"texto"}""";

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonSemFrente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frenteTransversalDeveRetornar400() throws Exception {
        when(service.analisar(anyString(), anyString(), eq(Frente.TRANSVERSAL), any(), anyBoolean()))
                .thenThrow(new FrenteInvalidaException(Frente.TRANSVERSAL));

        MatchRequest request = new MatchRequest("everson", "texto", Frente.TRANSVERSAL, null, null);

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerSemProfileDeveRetornar404() throws Exception {
        when(service.analisar(eq("desconhecido"), anyString(), eq(Frente.TECH), any(), anyBoolean()))
                .thenThrow(new ProfileNotFoundException("desconhecido"));

        MatchRequest request = new MatchRequest("desconhecido", "texto", Frente.TECH, null, null);

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void falhaNaClaudeApiDeveRetornar502() throws Exception {
        when(service.analisar(eq("everson"), anyString(), eq(Frente.TECH), any(), anyBoolean()))
                .thenThrow(new FitAnalysisException("timeout"));

        MatchRequest request = new MatchRequest("everson", "texto", Frente.TECH, null, null);

        mockMvc.perform(post("/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway());
    }
}
