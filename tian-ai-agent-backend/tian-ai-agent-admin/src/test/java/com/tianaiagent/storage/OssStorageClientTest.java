package com.tianaiagent.storage;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssStorageClientTest {
    @Test
    void localModeDoesNotRequireOssCredentials() throws Exception {
        Path source = testFile();
        try {
            OssStorageClient storage = new OssStorageClient();
            ReflectionTestUtils.setField(storage, "storageMode", "local");

            assertThat(storage.prepareSource("knowledge-base/medical.pdf", source))
                    .isEqualTo(source.toAbsolutePath().normalize().toUri().toString());
            assertThat(storage.storageMode()).isEqualTo("local");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void ossModeReportsMissingEnvironmentConfigurationClearly() throws Exception {
        Path source = testFile();
        try {
            OssStorageClient storage = new OssStorageClient();
            ReflectionTestUtils.setField(storage, "storageMode", "oss");
            ReflectionTestUtils.setField(storage, "endpoint", "");
            ReflectionTestUtils.setField(storage, "bucket", "");
            ReflectionTestUtils.setField(storage, "accessKeyId", "");
            ReflectionTestUtils.setField(storage, "accessKeySecret", "");

            assertThatThrownBy(() -> storage.prepareSource("knowledge-base/medical.pdf", source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void ossModeUploadsVerifiesAndReturnsPresignedUrl() throws Exception {
        Path source = testFile();
        try {
            OSS client = mock(OSS.class);
            when(client.doesObjectExist("medical-bucket", "knowledge-base/medical.pdf"))
                    .thenReturn(true);
            when(client.generatePresignedUrl(eq("medical-bucket"),
                    eq("knowledge-base/medical.pdf"), any()))
                    .thenReturn(new URL("https://medical-bucket.oss.example/medical.pdf?signature=test"));

            OssStorageClient storage = new OssStorageClient();
            ReflectionTestUtils.setField(storage, "storageMode", "oss");
            ReflectionTestUtils.setField(storage, "endpoint", "oss.example");
            ReflectionTestUtils.setField(storage, "bucket", "medical-bucket");
            ReflectionTestUtils.setField(storage, "accessKeyId", "test-key");
            ReflectionTestUtils.setField(storage, "accessKeySecret", "test-secret");
            ReflectionTestUtils.setField(storage, "ossClient", client);

            assertThat(storage.prepareSource("knowledge-base/medical.pdf", source))
                    .startsWith("https://medical-bucket.oss.example/medical.pdf");
            verify(client).putObject(eq("medical-bucket"),
                    eq("knowledge-base/medical.pdf"), any(java.io.InputStream.class));
            verify(client).doesObjectExist("medical-bucket", "knowledge-base/medical.pdf");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void ossModeRejectsInsecurePresignedUrl() throws Exception {
        Path source = testFile();
        try {
            OSS client = mock(OSS.class);
            when(client.doesObjectExist("medical-bucket", "knowledge-base/medical.pdf"))
                    .thenReturn(true);
            when(client.generatePresignedUrl(eq("medical-bucket"),
                    eq("knowledge-base/medical.pdf"), any()))
                    .thenReturn(new URL("http://medical-bucket.oss.example/medical.pdf?signature=test"));

            OssStorageClient storage = new OssStorageClient();
            ReflectionTestUtils.setField(storage, "storageMode", "oss");
            ReflectionTestUtils.setField(storage, "endpoint", "oss.example");
            ReflectionTestUtils.setField(storage, "bucket", "medical-bucket");
            ReflectionTestUtils.setField(storage, "accessKeyId", "test-key");
            ReflectionTestUtils.setField(storage, "accessKeySecret", "test-secret");
            ReflectionTestUtils.setField(storage, "ossClient", client);

            assertThatThrownBy(() -> storage.prepareSource("knowledge-base/medical.pdf", source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTPS");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private Path testFile() throws Exception {
        Path directory = Path.of("target", "storage-tests");
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(UUID.randomUUID() + ".pdf"), "test");
    }
}
