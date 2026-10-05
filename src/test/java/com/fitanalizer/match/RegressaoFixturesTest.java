package com.fitanalizer.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Roda no {@code mvn test} normal e no CI (sem tag, sem API). Duas coisas:
 * <ol>
 * <li><b>Guarda:</b> o repositório é público; as fixtures reais da regressão
 * (vagas de terceiros, decisões, Profile) pertencem a {@code regressao-local/},
 * ignorada pelo git. Se uma vaga real for commitada em
 * {@code src/test/resources/regressao/} por engano, este teste quebra o CI.</li>
 * <li>A regra de escolha da fonte do {@link RegressaoPromptTest} (local ou
 * exemplos, nunca as duas), testada com diretórios temporários.</li>
 * </ol>
 */
class RegressaoFixturesTest {

    // Lido do disco (working tree), não do classpath: é o que o git versiona.
    private static final Path EXEMPLOS_VERSIONADOS = Path.of("src/test/resources/regressao");

    // --- Guarda ---

    @Test
    void vagasVersionadasSaoSoExemplos() throws IOException {
        List<Path> vagas;
        try (Stream<Path> arquivos = Files.list(EXEMPLOS_VERSIONADOS)) {
            vagas = arquivos.filter(p -> p.getFileName().toString().matches("vaga-\\d+\\.txt")).sorted().toList();
        }
        assertThat(vagas).as("nenhum vaga-NN.txt em %s", EXEMPLOS_VERSIONADOS).isNotEmpty();

        for (Path vaga : vagas) {
            String texto = Files.readString(vaga, StandardCharsets.UTF_8);
            assertThat(texto.startsWith("EXEMPLO"))
                    .as("%s não começa com \"EXEMPLO\": parece uma vaga REAL. O repositório é público; "
                            + "fixtures reais pertencem a regressao-local/ (ignorada pelo git), nunca a "
                            + "src/test/resources/regressao/. Restaure o exemplo: "
                            + "git checkout -- src/test/resources/regressao", vaga)
                    .isTrue();
        }
    }

    // --- Escolha da fonte ---

    private static void criarFixtureLocal(Path dir) throws IOException {
        Files.writeString(dir.resolve("esperado.properties"), "vaga-01=cv_carta\n");
        Files.writeString(dir.resolve("vaga-01.txt"), "vaga local\n");
    }

    @Test
    void pastaLocalComEsperadoEhUsada(@TempDir Path raiz) throws IOException {
        Path local = Files.createDirectory(raiz.resolve("regressao-local"));
        criarFixtureLocal(local);

        RegressaoPromptTest.Fonte fonte = RegressaoPromptTest.escolherFonte(null, local);

        assertThat(fonte.local()).isTrue();
        assertThat(fonte.dir()).isEqualTo(local);
    }

    @Test
    void semPastaLocalCaiNosExemplos(@TempDir Path raiz) {
        RegressaoPromptTest.Fonte fonte = RegressaoPromptTest.escolherFonte(null, raiz.resolve("nao-existe"));

        assertThat(fonte.local()).isFalse();
    }

    @Test
    void pastaLocalSemEsperadoCaiNosExemplos(@TempDir Path raiz) throws IOException {
        // Por quê: o gatilho é o esperado.properties, não a existência da pasta.
        Path local = Files.createDirectory(raiz.resolve("regressao-local"));
        Files.writeString(local.resolve("vaga-01.txt"), "vaga solta\n");

        assertThat(RegressaoPromptTest.escolherFonte("", local).local()).isFalse();
    }

    @Test
    void dirPorPropriedadeTemPrioridadeSobreOPadrao(@TempDir Path raiz) throws IOException {
        Path padrao = Files.createDirectory(raiz.resolve("regressao-local"));
        criarFixtureLocal(padrao);
        Path outra = Files.createDirectory(raiz.resolve("outra"));
        criarFixtureLocal(outra);

        RegressaoPromptTest.Fonte fonte = RegressaoPromptTest.escolherFonte(outra.toString(), padrao);

        assertThat(fonte.local()).isTrue();
        assertThat(fonte.dir()).isEqualTo(outra);
    }

    @Test
    void dirPorPropriedadeSemEsperadoFalhaEmVezDeCairNosExemplos(@TempDir Path raiz) {
        // Por quê: quem passou -Dregressao.dir quer aquela pasta; cair em silêncio nos
        // exemplos esconderia um caminho errado e pagaria uma rodada inútil.
        assertThatThrownBy(() -> RegressaoPromptTest.escolherFonte(raiz.resolve("vazia").toString(), raiz))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("esperado.properties");
    }

    @Test
    void fonteLocalNaoMisturaComExemplos(@TempDir Path raiz) throws IOException {
        // Por quê: vaga-02.txt existe nos exemplos do classpath, mas a fonte é local;
        // ausente na pasta local tem de ser erro, nunca o exemplo no lugar.
        criarFixtureLocal(raiz);
        RegressaoPromptTest.Fonte fonte = RegressaoPromptTest.escolherFonte(raiz.toString(), raiz);

        try (InputStream entrada = RegressaoPromptTest.abrir(fonte, "vaga-01.txt")) {
            assertThat(new String(entrada.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("vaga local\n");
        }
        assertThatThrownBy(() -> RegressaoPromptTest.abrir(fonte, "vaga-02.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fixture local não encontrada");
    }

    @Test
    void fonteExemplosLeDoClasspath() throws IOException {
        RegressaoPromptTest.Fonte exemplos = new RegressaoPromptTest.Fonte(false, null);

        try (InputStream entrada = RegressaoPromptTest.abrir(exemplos, "vaga-01.txt")) {
            assertThat(new String(entrada.readAllBytes(), StandardCharsets.UTF_8)).startsWith("EXEMPLO");
        }
    }
}
