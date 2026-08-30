package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CLI 控制台 UTF-8 直写通道的编码契约测试。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class CliSupportUtf8Test {

    @Test
    @DisplayName("UTF-8 直写流：中文与符号按 UTF-8 字节编码输出")
    void utf8PrintStream_encodesChineseAsUtf8() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = CliSupport.utf8PrintStream(bytes);

        out.print("判定 PASS：差异 0 项");
        out.flush();

        assertEquals("判定 PASS：差异 0 项", new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
}
