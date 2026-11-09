package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.tianaiagent.types.storage.FileConstant;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

final class ModelSettingsCipher {
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    static final Path DIRECTORY = Path.of(FileConstant.FILE_SAVE_DIR, "model-settings");
    private static final Path KEY_FILE = DIRECTORY.resolve("storage.key");
    private static final SecureRandom RANDOM = new SecureRandom();

    byte[] seal(byte[] plaintext, byte[] associatedData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, keyForWrite(), nonce, associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] envelope = Arrays.copyOf(nonce, NONCE_BYTES + ciphertext.length);
            System.arraycopy(ciphertext, 0, envelope, NONCE_BYTES, ciphertext.length);
            return envelope;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("模型配置加密失败", e);
        }
    }

    byte[] open(byte[] envelope, byte[] associatedData) {
        if (envelope.length < NONCE_BYTES + 16) throw new IllegalStateException("模型配置密文无效");
        try {
            var cipher = cipher(Cipher.DECRYPT_MODE, Files.readAllBytes(KEY_FILE),
                    Arrays.copyOf(envelope, NONCE_BYTES), associatedData);
            return cipher.doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("无法解密模型配置，请检查数据目录中的存储密钥", e);
        }
    }

    private static synchronized byte[] keyForWrite() throws IOException {
        if (Files.exists(KEY_FILE)) return Files.readAllBytes(KEY_FILE);
        Files.createDirectories(DIRECTORY);
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        Path temporary = Files.getFileStore(DIRECTORY).supportsFileAttributeView("posix")
                ? Files.createTempFile(DIRECTORY, ".key-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(DIRECTORY, ".key-", ".tmp");
        try {
            Files.write(temporary, key);
            Files.move(temporary, KEY_FILE, StandardCopyOption.ATOMIC_MOVE);
            return key;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] associatedData)
            throws GeneralSecurityException {
        if (key.length != KEY_BYTES) throw new GeneralSecurityException("Invalid storage key");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (associatedData != null) cipher.updateAAD(associatedData);
        return cipher;
    }
}
