package com.tianaiagent.infrastructure.adapter.port.knowledge;

import com.tianaiagent.domain.knowledge.adapter.port.DocumentSourceStorage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.common.comm.Protocol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/**
 * 描述：阿里云 OSS 对象存储客户端
 *
 * 默认将医疗文档上传到 OSS，并通过预签名 URL 交给 Java 文档 MCP 下载解析。
 * 本地直传仅作为显式启用的开发调试模式。
 */
@Component
@Slf4j
public class OssStorageClient implements DocumentSourceStorage {

    @Value("${knowledge-base.storage.mode:local}")
    private String storageMode;

    @Value("${knowledge-base.oss.endpoint}")
    private String endpoint;

    @Value("${knowledge-base.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${knowledge-base.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${knowledge-base.oss.bucket:}")
    private String bucket;

    @Value("${knowledge-base.oss.url-expiration-seconds:3600}")
    private long urlExpirationSeconds;

    private OSS ossClient;

    /**
     * 默认上传OSS并返回预签名URL；local模式返回标准 file URI，
     * 避免 Windows 盘符和反斜杠被下游误当成不合法 URI。
     */
    public String prepareSource(String objectName, Path localPath) {
        Path normalized = localPath.toAbsolutePath().normalize();
        if (!usesOss()) {
            if (!Files.isRegularFile(normalized)) {
                throw new IllegalStateException("待解析的本地文档不存在: " + normalized);
            }
            return normalized.toUri().toString();
        }
        requireOssCredentials();  // 校验参数
        try (InputStream content = Files.newInputStream(normalized)) {
            return uploadAndGetUrl(objectName, content);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("读取待上传文档失败: " + normalized, e);
        }
    }

    public boolean usesOss() {
        String mode = storageMode == null ? "" : storageMode.trim();
        if ("oss".equalsIgnoreCase(mode)) return true;
        if ("local".equalsIgnoreCase(mode)) return false;
        throw new IllegalStateException("knowledge-base.storage.mode 只允许 oss 或 local，当前值: " + mode);
    }

    public String storageMode() {
        return usesOss() ? "oss" : "local";
    }

    private synchronized OSS getClient() {
        requireOssCredentials();
        if (ossClient == null) {
            ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
            configuration.setProtocol(Protocol.HTTPS);
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret, configuration);
        }
        return ossClient;
    }

    /**
     * 上传文件到 OSS 并返回可访问的预签名 URL
     *
     * @param objectName OSS 对象名（如 knowledge-base/xxx.pdf）
     * @param content    文件内容流
     * @return 预签名下载 URL（有效期见 urlExpirationSeconds）
     */
    public String uploadAndGetUrl(String objectName, InputStream content) {
        OSS client = getClient();
        client.putObject(bucket, objectName, content);
        if (!client.doesObjectExist(bucket, objectName)) {
            throw new IllegalStateException("OSS上传后校验失败，对象不存在: " + objectName);
        }
        Date expiration = new Date(System.currentTimeMillis() + urlExpirationSeconds * 1000L);  // 获取预签名
        URL url = client.generatePresignedUrl(bucket, objectName, expiration);  // 获取预签名的url
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalStateException("OSS预签名下载地址必须使用HTTPS");
        }
        log.info("知识库原文已上传并通过OSS存在性校验: bucket={}, object={}", bucket, objectName);
        return url.toString();
    }

    /**
     * 删除 OSS 对象
     *
     * @param objectName OSS 对象名
     */
    public void delete(String objectName) {
        if (!usesOss()) return;
        getClient().deleteObject(bucket, objectName);
    }

    private void requireOssCredentials() {
        if (!usesOss()) return;
        if (isBlank(endpoint) || isBlank(bucket) || isBlank(accessKeyId) || isBlank(accessKeySecret)) {
            throw new IllegalStateException(
                    "KB_STORAGE_MODE=oss 时必须配置 OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET、OSS_BUCKET 和 OSS endpoint");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
