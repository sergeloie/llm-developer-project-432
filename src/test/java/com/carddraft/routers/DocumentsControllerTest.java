package com.carddraft.routers;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.carddraft.documents.ChunkingSettings;
import com.carddraft.documents.DocumentService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The upload refusal names the formats the service actually accepts.
 *
 * <p>The service is the sole source, so this stubs it with a deliberately unfamiliar set and proves
 * the message renders that set rather than a list kept beside the check. A format accepted but not
 * advertised, or advertised but not accepted, is the drift that would otherwise reappear here.
 */
class DocumentsControllerTest {

    private final DocumentService documents = mock(DocumentService.class);

    private final DocumentsController controller =
            new DocumentsController(documents, null, new ChunkingSettings(1200, 200, 1024));

    @Test
    void theRefusalNamesTheSetTheServiceAdvertises() {
        given(documents.supportedFormats()).willReturn(List.of("klingon", "vulcan"));

        assertThatThrownBy(() -> controller.upload(file("supplier.exe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplier.exe")
                .hasMessageContaining("klingon")
                .hasMessageContaining("vulcan");
    }

    @Test
    void aFileTheServiceRejectsNeverReachesRegistration() throws Exception {
        assertThatThrownBy(() -> controller.upload(file("supplier.exe"))).isInstanceOf(IllegalArgumentException.class);

        verify(documents).supports("supplier.exe");
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, null, new byte[] {1, 2, 3});
    }
}
