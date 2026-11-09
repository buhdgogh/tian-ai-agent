package com.tianaiagent.infrastructure.gateway.document;

import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SourceLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsNativeAbsolutePathWithoutTreatingWindowsDriveAsUriScheme() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("medical.pdf"), "medical-document");
        DocMcpProperties properties = new DocMcpProperties();
        properties.setLocalSourceRoot(temporaryDirectory.toString());
        SourceLoader loader = new SourceLoader(mock(HttpClient.class), properties);

        SourceLoader.LoadedSource loaded = loader.load(source.toAbsolutePath().toString());

        assertThat(loaded.bytes()).isEqualTo(Files.readAllBytes(source));
        assertThat(loaded.fileName()).isEqualTo("medical.pdf");
        assertThat(loaded.publicUri()).isEqualTo(source.toAbsolutePath().normalize().toUri().toString());
    }

    @Test
    void rejectsRegularFilesOutsideConfiguredUploadDirectory() throws Exception {
        Path uploads = Files.createDirectory(temporaryDirectory.resolve("uploads"));
        Path privateFile = Files.writeString(temporaryDirectory.resolve("private.txt"), "private");
        DocMcpProperties properties = new DocMcpProperties();
        properties.setLocalSourceRoot(uploads.toString());
        var loader = new SourceLoader(mock(HttpClient.class), properties);

        assertThatThrownBy(() -> loader.load(privateFile.toString())).isInstanceOf(IllegalArgumentException.class);
    }
}
