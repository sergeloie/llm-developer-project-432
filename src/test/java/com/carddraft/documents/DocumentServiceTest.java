package com.carddraft.documents;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.repositories.DocumentsRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration and processing against a real database and the real supplied files.
 *
 * <p>The refusal case is the one worth having here: a scan with no text layer must leave a row in
 * the rejected state carrying a reason, and no chunks. An empty chunk list with a state of
 * "indexed" is the failure this guards against, and only a real parse can produce it.
 *
 * <p>Nothing here hands {@code process} the bytes to parse. They are fetched from the row the upload
 * created, which is the arrangement the whole upload path depends on and the one this test would
 * still pass without if the service quietly accepted a file it had nowhere to keep.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DocumentServiceTest {

    private static final Path DATA = Path.of("data");

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @Autowired
    DocumentService documentService;

    @Autowired
    DocumentsRepository documents;

    @Autowired
    JdbcClient jdbc;

    static boolean dataIsPresent() {
        return Files.isDirectory(DATA) && Files.exists(DATA.resolve("boiler_scan.pdf"));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();
    }

    @Test
    @EnabledIf("dataIsPresent")
    void aParseableDocumentIsParsedIntoChunksButNotYetIndexed() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("blender_passport.pdf"));
        var registered = documentService.register("blender_passport.pdf", content);

        var parsed = documentService.process(registered.id());

        assertThat(documents.chunksOf(registered.id()))
                .as("the fragments exist before anything can search them")
                .isNotEmpty();
        assertThat(parsed.rejectionReason()).isNull();

        assertThat(parsed.state())
                .as("'indexed' has to mean searchable, and a stored chunk with no vector is not "
                        + "searchable by meaning — the vector index is built WHERE embedding IS NOT "
                        + "NULL, so an unembedded document contributes nothing to hybrid retrieval "
                        + "while claiming to be indexed")
                .isEqualTo("parsing");

        assertThat(documentService.settle(registered.id()).state())
                .as("and settling before the vectors exist cannot promote it, whatever the caller "
                        + "believes about it")
                .isEqualTo("parsing");
    }

    @Test
    @EnabledIf("dataIsPresent")
    void theContentIsKeptSoTheParseCanHappenAfterTheUploadHasBeenAnswered() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("blender_passport.pdf"));
        var registered = documentService.register("blender_passport.pdf", content);

        assertThat(documents.contentOf(registered.id()))
                .as("the file the upload arrived as, read back unchanged")
                .contains(content);
    }

    @Test
    @EnabledIf("dataIsPresent")
    void theScanIsRecordedAsRejectedWithAReasonAndNoChunks() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("boiler_scan.pdf"));
        var registered = documentService.register("boiler_scan.pdf", content);

        var processed = documentService.process(registered.id());

        assertThat(processed.state()).isEqualTo("rejected");
        assertThat(processed.rejectionReason()).containsIgnoringCase("text layer");
        assertThat(processed.chunkCount()).isZero();
        assertThat(documents.chunksOf(registered.id()))
                .as("a refused document must not leave chunks behind to be found by search")
                .isEmpty();
    }

    @Test
    void reRegisteringTheSameContentReturnsTheSameDocument() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("kettle_spec.xlsx"));

        var first = documentService.register("kettle_spec.xlsx", content);
        var second = documentService.register("kettle_spec.xlsx", content);
        var renamed = documentService.register("a-different-name.xlsx", content);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(renamed.id())
                .as("content decides identity, not the filename")
                .isEqualTo(first.id());
        assertThat(documents.findByContentHash(
                        documents.findById(first.id()).orElseThrow().contentSha256()))
                .isPresent();
    }

    @Test
    @EnabledIf("dataIsPresent")
    void anUnsupportedExtensionIsRefusedWithAReason() {
        var registered = documentService.register("supplier.exe", new byte[] {1, 2, 3});

        var processed = documentService.process(registered.id());

        assertThat(processed.state()).isEqualTo("rejected");
        assertThat(processed.rejectionReason()).containsIgnoringCase("unsupported");
    }

    @Test
    void aDocumentWhoseContentIsGoneIsRefusedWithAReasonRatherThanParsedAsNothing() {
        String id = "doc-no-content";
        documents.create(id, "gone.pdf", "a".repeat(64), 7, null);

        var processed = documentService.process(id);

        assertThat(processed.state()).isEqualTo("rejected");
        assertThat(processed.rejectionReason()).containsIgnoringCase("content");
        assertThat(processed.chunkCount()).isZero();
    }
}
