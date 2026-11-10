package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.tianaiagent.domain.agent.adapter.repository.ToolCredentialStore;
import com.tianaiagent.domain.agent.model.valobj.PersonalTool;
import com.tianaiagent.infrastructure.dao.mysql.ToolCredentialMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

public class EncryptedToolCredentialStore implements ToolCredentialStore {
    private final ToolCredentialMapper mysql;
    private final ModelSettingsCipher cipher = new ModelSettingsCipher();
    private final Path directory = ModelSettingsCipher.DIRECTORY.resolve("tool-credentials");

    public EncryptedToolCredentialStore(ToolCredentialMapper mysql) { this.mysql = mysql; }

    @Override
    public boolean exists(String ownerId, PersonalTool tool) {
        return mysql != null ? mysql.find(ownerId, tool.name()) != null : Files.isRegularFile(file(ownerId, tool));
    }

    @Override
    public String find(String ownerId, PersonalTool tool) {
        byte[] data;
        if (mysql != null) {
            data = mysql.find(ownerId, tool.name());
        } else {
            try {
                data = Files.readAllBytes(file(ownerId, tool));
            } catch (NoSuchFileException absent) {
                return null;
            } catch (IOException e) {
                throw new IllegalStateException("无法读取工具凭据", e);
            }
        }
        return data == null ? null : new String(cipher.open(data, context(ownerId, tool)), UTF_8);
    }

    @Override
    public void save(String ownerId, PersonalTool tool, String value) {
        byte[] data = cipher.seal(value.getBytes(UTF_8), context(ownerId, tool));
        if (mysql != null) {
            mysql.save(ownerId, tool.name(), data);
            return;
        }
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".credential-", ".tmp");
            try {
                Files.write(temporary, data);
                Files.move(temporary, file(ownerId, tool), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存工具凭据", e);
        }
    }

    @Override
    public void delete(String ownerId, PersonalTool tool) {
        if (mysql != null) {
            mysql.delete(ownerId, tool.name());
            return;
        }
        try {
            Files.deleteIfExists(file(ownerId, tool));
        } catch (IOException e) {
            throw new IllegalStateException("无法清除工具凭据", e);
        }
    }

    private Path file(String ownerId, PersonalTool tool) {
        return directory.resolve(HexFormat.of().formatHex(ownerId.getBytes(UTF_8)) + "-" + tool.name() + ".enc");
    }

    private static byte[] context(String ownerId, PersonalTool tool) {
        return String.join("\n", "tool-credential", ownerId, tool.name()).getBytes(UTF_8);
    }
}
