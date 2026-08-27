package io.github.agentassert4j.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 哈希工具 — ThreadLocal 复用 MessageDigest 实例。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class HashUtil {

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    private HashUtil() {
    }

    /**
     * 计算输入字符串的 SHA-256 哈希，返回小写十六进制字符串。
     * 输入为 null 时返回 "unknown"。
     */
    public static String sha256(String input) {
        if (input == null) {
            return "unknown";
        }
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }
}
