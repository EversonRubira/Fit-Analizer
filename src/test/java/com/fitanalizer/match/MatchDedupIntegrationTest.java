package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitanalizer.profile.Frente;
import com.fitanalizer.profile.Profile;
import com.fitanalizer.profile.ProfileRepository;
import com.fitanalizer.profile.Skill;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova, contra um Postgres real, o dedup "uma análise por (profile, vaga)".
 *
 * <p>Por que não Mockito nem H2: o que queremos provar é o comportamento do
 * Postgres com a UNIQUE e o momento em que o Hibernate faz o flush. Um mock
 * ou outro banco passaria mesmo com o schema real errado.
 *
 * <p>Estes testes NÃO usam {@code @Transactional} de propósito: precisam de
 * commits reais. Dentro de uma transação de teste, as threads não veriam os
 * dados uma da outra e o flush que queremos observar seria mascarado. Por
 * isso a limpeza é manual, no {@code @AfterEach}.
 *
 * <p>Só o {@link FitAnalysisClient} é mockado: nenhuma chamada real à Claude API.
 */
@SpringBootTest
@Testcontainers
class MatchDedupIntegrationTest {

    // static: um único container para a classe toda (subir Postgres a cada teste seria lento).
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Aponta o DataSource da aplicação para o container. O schema é criado pelo
    // mesmo mecanismo da aplicação (ddl-auto: update, do application.yml).
    @DynamicPropertySource
    static void propriedadesDoBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    private FitAnalysisClient fitAnalysisClient;

    @Autowired
    private MatchService matchService;

    @Autowired
    private MatchResultRepository matchResultRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private Profile profile;

    @BeforeEach
    void setUp() {
        Profile novo = new Profile("everson");
        novo.addSkill(new Skill("Java", 5, Frente.TECH));
        profile = profileRepository.save(novo);

        when(fitAnalysisClient.analisar(any())).thenReturn(new FitAnalysisResult(Frente.TECH,
                List.of(new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java")), List.of()));
    }

    @AfterEach
    void limpar() {
        // Ordem importa: match_results referencia profiles (FK).
        matchResultRepository.deleteAll();
        profileRepository.deleteAll();
    }

    @Test
    void mesmaVagaDuasVezesDevolveResultadoSalvoEChamaClaudeUmaVez() {
        // Por quê: é a regra de negócio — o coletor reenviar a mesma vaga não pode gastar API de novo.
        String vaga = "https://vaga.example/1";

        AnaliseResultado primeira = matchService.analisar("everson", "texto da vaga", Frente.TECH, vaga, false);
        AnaliseResultado segunda = matchService.analisar("everson", "texto da vaga", Frente.TECH, vaga, false);

        assertThat(primeira.analiseNova()).isTrue();
        assertThat(segunda.analiseNova()).isFalse();
        assertThat(segunda.matchResult().getId()).isEqualTo(primeira.matchResult().getId());
        verify(fitAnalysisClient, times(1)).analisar(any());
        assertThat(matchResultRepository.count()).isEqualTo(1);
    }

    @Test
    void bancoRecusaDoisMatchResultsComMesmoProfileEVaga() {
        // Por quê: prova que a UNIQUE existe de fato no Postgres, e não só na anotação da entidade.
        matchResultRepository.saveAndFlush(new MatchResult(profile, Frente.TECH, "chave-1", "chave-1"));

        assertThatThrownBy(
                () -> matchResultRepository.saveAndFlush(new MatchResult(profile, Frente.TECH, "chave-1", "chave-1")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(matchResultRepository.count()).isEqualTo(1);
    }

    @Test
    void duasRequisicoesSimultaneasDaMesmaVagaDeixamUmaLinhaESemExcecao() throws Exception {
        // Por quê: único teste que exercita o saveAndFlush + o catch de DataIntegrityViolationException.
        // O client demora um pouco de propósito para as duas threads passarem pela checagem
        // "já existe?" antes de qualquer uma salvar — é isso que força a corrida no banco.
        // Numa corrida a Claude API pode ser chamada 2 vezes (aceitável); por isso não se
        // assevera o número de chamadas aqui, só a consistência: 1 linha e nenhum erro.
        when(fitAnalysisClient.analisar(any())).thenAnswer(invocacao -> {
            Thread.sleep(500);
            return new FitAnalysisResult(Frente.TECH,
                    List.of(new RequisitoClassificado("Java", Classificacao.FORTE, "skill:Java")), List.of());
        });

        String vaga = "https://vaga.example/corrida";
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AnaliseResultado>> futuros = List.of(
                    pool.submit(() -> {
                        largada.await();
                        return matchService.analisar("everson", "texto da vaga", Frente.TECH, vaga, false);
                    }),
                    pool.submit(() -> {
                        largada.await();
                        return matchService.analisar("everson", "texto da vaga", Frente.TECH, vaga, false);
                    }));
            largada.countDown();

            for (Future<AnaliseResultado> futuro : futuros) {
                // get() relança qualquer exceção da thread: se o catch do Service não
                // segurar a violação da UNIQUE, o teste falha aqui.
                AnaliseResultado resultado = futuro.get(30, TimeUnit.SECONDS);
                assertThat(resultado.matchResult().getVagaChave()).isEqualTo(vaga);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(matchResultRepository.count()).isEqualTo(1);
    }
}
