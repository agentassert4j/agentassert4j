package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataSanitizer 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class DataSanitizerTest {

    private RecorderConfig configWithFields(SanitizeStrategy strategy, String... fields) {
        return RecorderConfig.builder()
                .sensitiveFields(Arrays.asList(fields))
                .sanitizeStrategy(strategy)
                .build();
    }

    private RecorderConfig configWithUserInputSanitize(String... fields) {
        return RecorderConfig.builder()
                .sensitiveFields(Arrays.asList(fields))
                .sanitizeUserInput(true)
                .sanitizeModelResponse(true)
                .build();
    }

    private InteractionRecord createTestRecord() {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-001");
        record.setTimestamp(System.currentTimeMillis());
        record.setTemplateHash("abc123");
        record.setUserInput("My password is secret123");
        record.setModelResponse("Your token is tok_abc");
        record.setHasToolCalls(true);

        ToolCall tc = new ToolCall();
        tc.setToolName("queryDB");
        tc.setToolCallId("tc-001");
        tc.setSuccess(true);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("password", "secret123");
        args.put("query", "SELECT * FROM users");
        args.put("token", "tok_abc");
        tc.setArguments(args);

        tc.setResult("{\"status\":\"ok\",\"token\":\"tok_abc\",\"data\":\"visible\"}");

        record.setToolCalls(List.of(tc));
        return record;
    }

    @Test
    void sanitize_nullRecord_returnsNull() {
        DataSanitizer sanitizer = new DataSanitizer(RecorderConfig.defaults());
        assertNull(sanitizer.sanitize(null));
    }

    @Test
    void sanitize_nullConfig_noException() {
        DataSanitizer sanitizer = new DataSanitizer(null);
        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);
        assertNotNull(result);
        // 无敏感字段配置，原样返回
        assertSame(record, result);
    }

    @Test
    void sanitize_noSensitiveFields_returnsSameInstance() {
        DataSanitizer sanitizer = new DataSanitizer(RecorderConfig.defaults());
        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);
        assertSame(record, result);
    }

    @Test
    void sanitize_maskStrategy_masksSensitiveArgs() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password", "token"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        // 原始记录不被修改
        assertEquals("secret123", record.getToolCalls().get(0).getArguments().get("password"));

        // 脱敏后 password 和 token 被替换为 ***
        ToolCall sanitizedTc = result.getToolCalls().get(0);
        assertEquals("***", sanitizedTc.getArguments().get("password"));
        assertEquals("SELECT * FROM users", sanitizedTc.getArguments().get("query"));
        assertEquals("***", sanitizedTc.getArguments().get("token"));
    }

    @Test
    void sanitize_maskStrategy_masksSensitiveFieldsInResult() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "token"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        ToolCall sanitizedTc = result.getToolCalls().get(0);
        String sanitizedResult = sanitizedTc.getResult();
        // token 值应该被替换
        assertFalse(sanitizedResult.contains("tok_abc"));
        assertTrue(sanitizedResult.contains("***"));
        // 其他字段保持不变
        assertTrue(sanitizedResult.contains("visible"));
    }

    @Test
    void sanitize_hashStrategy_hashesSensitiveArgs() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.HASH, "password"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        ToolCall sanitizedTc = result.getToolCalls().get(0);
        String hashedPassword = (String) sanitizedTc.getArguments().get("password");

        // SHA-256 哈希应该是 64 字符的十六进制字符串
        assertNotNull(hashedPassword);
        assertEquals(64, hashedPassword.length());
        // query 不受影响
        assertEquals("SELECT * FROM users", sanitizedTc.getArguments().get("query"));
    }

    @Test
    void sanitize_dropStrategy_removesSensitiveArgs() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.DROP, "password", "token"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        ToolCall sanitizedTc = result.getToolCalls().get(0);
        assertFalse(sanitizedTc.getArguments().containsKey("password"));
        assertFalse(sanitizedTc.getArguments().containsKey("token"));
        // query 保留
        assertTrue(sanitizedTc.getArguments().containsKey("query"));
    }

    @Test
    void sanitize_caseInsensitiveMatch() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "Password", "TOKEN"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        ToolCall sanitizedTc = result.getToolCalls().get(0);
        assertEquals("***", sanitizedTc.getArguments().get("password"));
        assertEquals("***", sanitizedTc.getArguments().get("token"));
    }

    @Test
    void sanitize_userInputAndModelResponse_notSanitizedByDefault() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        InteractionRecord record = createTestRecord();
        InteractionRecord result = sanitizer.sanitize(record);

        // 默认不脱敏 userInput 和 modelResponse
        assertEquals("My password is secret123", result.getUserInput());
        assertEquals("Your token is tok_abc", result.getModelResponse());
    }

    @Test
    void sanitize_userInputAndModelResponse_sanitizedWhenEnabled() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithUserInputSanitize("password"));

        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-002");
        record.setUserInput("hello");
        record.setModelResponse("world");

        InteractionRecord result = sanitizer.sanitize(record);
        // 整个 userInput 被替换为 ***
        assertEquals("***", result.getUserInput());
        assertEquals("***", result.getModelResponse());
    }

    @Test
    void sanitize_doesNotModifyOriginal() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        InteractionRecord record = createTestRecord();
        sanitizer.sanitize(record);

        // 原始记录保持不变
        assertEquals("secret123", record.getToolCalls().get(0).getArguments().get("password"));
        assertEquals("My password is secret123", record.getUserInput());
    }

    @Test
    void sanitize_recordWithNoToolCalls() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-003");
        record.setToolCalls(null);

        InteractionRecord result = sanitizer.sanitize(record);
        assertNotNull(result);
        assertNull(result.getToolCalls());
    }

    @Test
    void sanitize_recordWithEmptyToolCalls() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-004");
        record.setToolCalls(List.of());

        InteractionRecord result = sanitizer.sanitize(record);
        assertNotNull(result);
        assertTrue(result.getToolCalls().isEmpty());
    }

    @Test
    void sanitize_toolCallWithNullArguments() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        ToolCall tc = new ToolCall();
        tc.setToolName("test");
        tc.setArguments(null);

        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-005");
        record.setToolCalls(List.of(tc));

        InteractionRecord result = sanitizer.sanitize(record);
        assertNotNull(result);
        assertNull(result.getToolCalls().get(0).getArguments());
    }

    @Test
    void sanitize_toolCallWithNullResult() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "password"));

        ToolCall tc = new ToolCall();
        tc.setToolName("test");
        tc.setResult(null);

        InteractionRecord record = new InteractionRecord();
        record.setRecordId("test-006");
        record.setToolCalls(List.of(tc));

        InteractionRecord result = sanitizer.sanitize(record);
        assertNotNull(result);
        assertNull(result.getToolCalls().get(0).getResult());
    }

    @Test
    void sanitizeJsonString_nestedJson() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "secret"));

        String json = "{\"name\":\"John\",\"secret\":\"mySecret\",\"age\":30}";
        String result = sanitizer.sanitizeJsonString(json);

        assertTrue(result.contains("***"));
        assertFalse(result.contains("mySecret"));
        assertTrue(result.contains("John"));
    }

    @Test
    void sanitizeJsonString_noMatch() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "nonexistent"));

        String json = "{\"name\":\"John\",\"age\":30}";
        String result = sanitizer.sanitizeJsonString(json);

        assertEquals(json, result);
    }

    @Test
    void sanitizeJsonString_nullOrEmpty() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.MASK, "secret"));

        assertNull(sanitizer.sanitizeJsonString(null));
        assertEquals("", sanitizer.sanitizeJsonString(""));
    }

    @Test
    void sanitizeJsonString_dropMiddleKey_noTrailingComma() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.DROP, "secret"));

        String json = "{\"name\":\"John\",\"secret\":\"mySecret\",\"age\":30}";
        String result = sanitizer.sanitizeJsonString(json);

        // 不应包含 secret 和尾逗号
        assertFalse(result.contains("secret"));
        assertFalse(result.contains(",,"));
        assertFalse(result.contains(",}"));
        assertTrue(result.contains("John"));
        assertTrue(result.contains("age"));
    }

    @Test
    void sanitizeJsonString_dropFirstKey_noLeadingComma() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.DROP, "secret"));

        String json = "{\"secret\":\"mySecret\",\"name\":\"John\"}";
        String result = sanitizer.sanitizeJsonString(json);

        assertFalse(result.contains("secret"));
        assertFalse(result.contains("{,"));
        assertTrue(result.contains("John"));
    }

    @Test
    void sanitizeJsonString_dropLastKey_noTrailingComma() {
        DataSanitizer sanitizer = new DataSanitizer(
                configWithFields(SanitizeStrategy.DROP, "secret"));

        String json = "{\"name\":\"John\",\"secret\":\"mySecret\"}";
        String result = sanitizer.sanitizeJsonString(json);

        assertFalse(result.contains("secret"));
        assertFalse(result.contains(",}"));
        assertTrue(result.contains("John"));
    }
}
