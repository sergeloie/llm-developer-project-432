package com.carddraft.documents;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Registration and processing against a real database and the real supplied files.
 *
 * <p>The refusal case is the one worth having here: a scan with no text layer must leave a row in
 * the rejected state carrying a reason, and no chunks. An empty chunk list with a state of
 * "indexed" is the failure this guards against, and only a real parse can produce it.
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

    static boolean dataIsPresent() {
        return Files.isDirectory(DATA) && Files.exists(DATA.resolve("boiler_scan.pdf"));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Autowired
    DocumentService documentService;

    @Autowired
    DocumentsRepository documents;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();
    }

    @Test
    @EnabledIf("dataIsPresent")
    void aParseableDocumentIsIndexedWithChunks() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("blender_passport.pdf"));
        var registered = documentService.register("blender_passport.pdf", content);

        var processed = documentService.process(registered.id(), "blender_passport.pdf", content);

        assertThat(processed.state()).isEqualTo("indexed");
        assertThat(processed.chunkCount()).isPositive();
        assertThat(processed.rejectionReason()).isNull();
        assertThat(documents.chunksOf(registered.id())).hasSize(processed.chunkCount());
    }

    @Test
    @EnabledIf("dataIsPresent")
    void theScanIsRecordedAsRejectedWithAReasonAndNoChunks() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("boiler_scan.pdf"));
        var registered = documentService.register("boiler_scan.pdf", content);

        var processed = documentService.process(registered.id(), "boiler_scan.pdf", content);

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
        assertThat(renamed.id()).as("content decides identity, not the filename").isEqualTo(first.id());
        assertThat(documents.findByContentHash(
                documents.findById(first.id()).orElseThrow().contentSha256())).isPresent();
    }

    @Test
    @EnabledIf("dataIsPresent")
    void anUnsupportedExtensionIsRefusedWithAReason() {
        var registered = documentService.register("supplier.exe", new byte[]{1, 2, 3});

        var processed = documentService.process(registered.id(), "supplier.exe", new byte[]{1, 2, 3});

        assertThat(processed.state()).isEqualTo("rejected");
        assertThat(processed.rejectionReason()).containsIgnoringCase("unsupported");
    }
}
