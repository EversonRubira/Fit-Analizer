package com.fitanalizer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.fitanalizer.match.FitAnalysisClient;
import com.fitanalizer.profile.dto.ProfileCreateRequest;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prova, contra um Postgres real e via HTTP de verdade, que duas criações
 * simultâneas do mesmo owner dão exatamente um 201 e um 409 — nunca 500.
 *
 * <p>Mesmo racional do {@code MatchDedupIntegrationTest}: o que está sob teste
 * é a UNIQUE do Postgres + o momento do flush, então nem mock nem H2 servem, e
 * o teste não é {@code @Transactional} (precisa de commits reais).
 *
 * <p>Como a corrida é forçada: o {@link ProfileRepository} é um spy, e o
 * {@code findByOwner} de cada thread espera a outra numa {@link CyclicBarrier}
 * antes de devolver. Assim as duas passam pela checagem "já existe?" vendo
 * "não" e as duas tentam o INSERT — a corrida acontece sempre, sem depender
 * de sleep nem de sorte no agendamento das threads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProfileConcorrenciaIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void propriedadesDoBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Não participa deste teste; mockado só para o contexto subir sem ANTHROPIC_API_KEY.
    @MockBean
    private FitAnalysisClient fitAnalysisClient;

    @SpyBean
    private ProfileRepository profileRepository;

    @Autowired
    private TestRestTemplate http;

    @AfterEach
    void limpar() {
        profileRepository.deleteAll();
    }

    @Test
    void duasCriacoesSimultaneasDoMesmoOwnerDaoUm201EUm409() throws Exception {
        CyclicBarrier ambasChecaram = new CyclicBarrier(2);
        // O repositório é um proxy de interface: callRealMethod() não funciona nele.
        // O spy do Spring Boot delega ao repositório real pela resposta padrão,
        // então é ela que chamamos para executar o findByOwner de verdade.
        Answer<?> repositorioReal = mockingDetails(profileRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocacao -> {
            Object resultado = repositorioReal.answer(invocacao);
            // Timeout: se só uma thread chegar aqui, o teste falha em vez de travar.
            ambasChecaram.await(10, TimeUnit.SECONDS);
            return resultado;
        }).when(profileRepository).findByOwner(anyString());

        ProfileCreateRequest corpo = new ProfileCreateRequest("everson", "bio");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> status;
        try {
            List<Future<HttpStatusCode>> futuros = List.of(
                    pool.submit(() -> http.postForEntity("/profiles", corpo, String.class).getStatusCode()),
                    pool.submit(() -> http.postForEntity("/profiles", corpo, String.class).getStatusCode()));
            status = List.of(futuros.get(0).get(30, TimeUnit.SECONDS).value(),
                    futuros.get(1).get(30, TimeUnit.SECONDS).value());
        } finally {
            pool.shutdownNow();
        }

        assertThat(status).containsExactlyInAnyOrder(201, 409);
        assertThat(profileRepository.count()).isEqualTo(1);
    }
}
