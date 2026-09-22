package com.tianaiagent.docmcp.parse;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SourceLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsNativeAbsolutePathWithoutTreatingWindowsDriveAsUriScheme() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("medical.pdf"), "medical-document");
        SourceLoader loader = new SourceLoader(mock(HttpClient.class), new DocMcpProperties());

        SourceLoader.LoadedSource loaded = loader.load(source.toAbsolutePath().toString());

        assertThat(loaded.bytes()).isEqualTo(Files.readAllBytes(source));
        assertThat(loaded.fileName()).isEqualTo("medical.pdf");
        assertThat(loaded.publicUri()).isEqualTo(source.toAbsolutePath().normalize().toUri().toString());
    }
}
