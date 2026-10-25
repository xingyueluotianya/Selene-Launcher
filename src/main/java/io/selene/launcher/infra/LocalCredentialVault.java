package io.selene.launcher.infra;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** AES-GCM local vault that keeps account secrets out of readable JSON and diagnostic exports. */
public final class LocalCredentialVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path keyFile;
    private volatile SecretKeySpec key;

    public LocalCredentialVault(Path keyFile) {
        this.keyFile = keyFile;
    }

    public String seal(String value) throws IOException {
        if (value == null || value.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            throw new IOException("无法加密账号凭据", failure);
        }
    }

    public String open(String value) throws IOException {
        if (value == null || value.isBlank()) return "";
        if (!value.startsWith("v1:")) return value;
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(3));
            if (payload.length <= IV_BYTES) throw new IOException("账号凭据密文格式无效");
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new IOException("无法解密账号凭据；本地密钥可能已损坏", failure);
        }
    }

    private SecretKeySpec key() throws IOException {
        SecretKeySpec current = key;
        if (current != null) return current;
        synchronized (this) {
            if (key != null) return key;
            Files.createDirectories(keyFile.toAbsolutePath().normalize().getParent());
            byte[] bytes;
            if (Files.isRegularFile(keyFile)) {
                bytes = Files.readAllBytes(keyFile);
                if (bytes.length != KEY_BYTES) throw new IOException("账号凭据密钥长度无效");
            } else {
                bytes = new byte[KEY_BYTES];
                RANDOM.nextBytes(bytes);
                Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictPermissions();
            }
            key = new SecretKeySpec(bytes, "AES");
            return key;
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows inherits the current user's application-data ACL.
        }
    }
}
