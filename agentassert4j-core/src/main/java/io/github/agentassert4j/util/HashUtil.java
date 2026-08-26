package io.github.agentassert4j.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 哈希工具 — ThreadLocal 复用 MessageDigest 实例。
 * 与方案文档 13.1.4 节一致。
 */
public final class HashUtil {

    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 not available", e);
                }
            });

    private HashUtil() {}

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
        return HexFormat.of().formatHex(hash);
    }
}
