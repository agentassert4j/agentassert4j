package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 披露等价钉 — seed record / approvedBy / 扇出披露 / member 窗口四能力在
 * CLI 人读、CLI JSON、MCP 三格抽查同形（登记表真源：guide/spec/equivalence.md
 * 披露字段面行；member 窗口的 MCP 格由 McpServerTest 窗口透传钉覆盖）。
 *
 * @author axy-yxa
 * @since 2026-09-17
 */
class DisclosureParityTest {

    @TempDir
    Path tempDir;

    private final PrintStream originalStdout = System.out;
    private final PrintStream originalStderr = System.err;
    private String dbPath;
    private SqliteStorageRepository repository;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("parity.db").toString();
        repository = new SqliteStorageRepository(dbPath);
        repository.initialize();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalStdout);
        System.setErr(originalStderr);
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("seed record：CLI 人读与 MCP establish 回告携带同一种子值")
    void seedRecord_cliHumanAndMcpCarrySameValue() {
        String mcpDb = tempDir.resolve("parity-mcp.db").toString();
        SqliteStorageRepository mcpRepo = new SqliteStorageRepository(mcpDb);
        mcpRepo.initialize();
        try {
            saveAgentRecord(mcpRepo, "rec-seed-1", "seedAgent", "hash-s", "session-1", 1000L);
        } finally {
            mcpRepo.close();
        }
        saveAgentRecord(repository, "rec-seed-1", "seedAgent", "hash-s", "session-1", 1000L);

        McpDispatcher dispatcher = new McpDispatcher(McpTools.tools(mcpDb), null);
        dispatcher.handle(rpc("initialize", "1"));
        Map<String, Object> result = callTool(dispatcher, "establish", "{\"approver\":\"agent:parity\"}");
        assertFalse(Boolean.TRUE.equals(result.get("isError")), "MCP establish 必须成功: " + result);
        Matcher mcpSeed = Pattern.compile("\"seedRecordId\":\"([^\"]*)\"").matcher(contentText(result));
        assertTrue(mcpSeed.find(), "MCP 回告必须带 seedRecordId: " + contentText(result));

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath, "--approver", "agent:parity");
        assertEquals(0, exit);
        assertTrue(out.toString().contains("seed record " + mcpSeed.group(1)), "CLI 人读披露同一颗种子: " + out);
    }

    @Test
    @DisplayName("approvedBy：status 人读列与 --json 携带同一审批人")
    void approvedBy_humanAndJsonCarrySameValue() {
        saveAgentRecord(repository, "rec-ap-1", "apAgent", "hash-ap", "session-1", 1000L);
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath, "--approver", "agent:parity"));

        ByteArrayOutputStream human = redirectStdout();
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath));
        ByteArrayOutputStream json = redirectStdout();
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath, "--json"));

        Matcher jsonApprover = Pattern.compile("\"approvedBy\":\"([^\"]*)\"").matcher(json.toString());
        assertTrue(jsonApprover.find(), "JSON 面必须带 approvedBy: " + json);
        assertEquals("agent:parity", jsonApprover.group(1));
        assertTrue(human.toString().contains(jsonApprover.group(1)), "人读 approver 列与 JSON 同值: " + human);
    }

    @Test
    @DisplayName("扇出披露：CLI 与 MCP establish 对同标签多键披露同一覆盖清单")
    void fanOut_cliAndMcpDiscloseSameCover() {
        String mcpDb = tempDir.resolve("parity-fan-mcp.db").toString();
        SqliteStorageRepository mcpRepo = new SqliteStorageRepository(mcpDb);
        mcpRepo.initialize();
        try {
            saveAgentRecord(mcpRepo, "rec-f1", "fanAgent", "hash-x", "session-1", 1000L);
            saveAgentRecord(mcpRepo, "rec-f2", "fanAgent", "hash-y", "session-1", 2000L);
        } finally {
            mcpRepo.close();
        }
        saveAgentRecord(repository, "rec-f1", "fanAgent", "hash-x", "session-1", 1000L);
        saveAgentRecord(repository, "rec-f2", "fanAgent", "hash-y", "session-1", 2000L);

        McpDispatcher dispatcher = new McpDispatcher(McpTools.tools(mcpDb), null);
        dispatcher.handle(rpc("initialize", "1"));
        Map<String, Object> result = callTool(dispatcher, "establish", "{\"invocation\":\"fanAgent\",\"approver\":\"agent:parity\"}");
        assertFalse(Boolean.TRUE.equals(result.get("isError")), "MCP establish 必须成功: " + result);
        String mcpText = contentText(result);
        assertTrue(mcpText.contains("covers 2 invocations"), "MCP 面扇出披露在场: " + mcpText);

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath, "--invocation", "fanAgent", "--approver", "agent:parity");
        assertEquals(0, exit);
        String cliText = out.toString();
        assertTrue(cliText.contains("covers 2 invocations"), "CLI 面扇出披露在场: " + cliText);
        for (String shortForm : shortForms(cliText)) {
            assertTrue(mcpText.contains(shortForm), "MCP 披露同一键短形 " + shortForm + ": " + mcpText);
        }
    }

    @Test
    @DisplayName("member 窗口：replay --member-check 人读与 --json 携带同一窗口与命中计数")
    void memberWindow_humanAndJsonCarrySameValues() {
        saveAgentRecord(repository, "rec-m1", "memAgent", "hash-m", "session-m1", 1000L);
        saveAgentRecord(repository, "rec-m2", "memAgent", "hash-m", "session-m2", 2000L);

        ByteArrayOutputStream human = redirectStdout();
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--member-check"));
        ByteArrayOutputStream json = redirectStdout();
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--member-check", "--json"));

        String humanText = human.toString();
        assertTrue(humanText.contains("(window 5)"), "人读默认窗口 5: " + humanText);
        assertTrue(humanText.contains("matches 1 of 1"), "人读命中计数 1 of 1: " + humanText);
        String jsonText = json.toString();
        assertTrue(jsonText.contains("\"window\":5"), "JSON 同窗口: " + jsonText);
        assertTrue(jsonText.contains("\"matched\":1"), "JSON 同命中计数: " + jsonText);
        assertTrue(jsonText.contains("\"isMember\":true"), "JSON 成员判定在场: " + jsonText);
    }

    private void saveAgentRecord(SqliteStorageRepository repo, String recordId, String label, String templateHash, String sessionId, long ts) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setInvocationId(label);
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        r.setTemplateHash(templateHash);
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("{\"result\":\"ok\"}");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repo.saveInteractionIfAbsent(r);
    }

    private ByteArrayOutputStream redirectStdout() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        return buffer;
    }

    private String rpc(String method, String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(McpDispatcher dispatcher, String name, String argumentsJson) {
        String response = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}}");
        Object parsed = RecursiveJsonParser.parse(response);
        assertTrue(parsed instanceof Map, "tool call response must be a JSON object: " + response);
        Object result = ((Map<String, Object>) parsed).get("result");
        assertTrue(result instanceof Map, "result must be an object: " + response);
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private String contentText(Map<String, Object> toolResult) {
        StringBuilder sb = new StringBuilder();
        Object content = toolResult.get("content");
        if (content instanceof List) {
            for (Object item : (List<Object>) content) {
                if (item instanceof Map) {
                    Object text = ((Map<String, Object>) item).get("text");
                    if (text != null) {
                        sb.append(text).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private List<String> shortForms(String cliText) {
        List<String> forms = new ArrayList<>();
        Matcher matcher = Pattern.compile("fanAgent@[A-Za-z0-9][A-Za-z0-9-]*").matcher(cliText);
        while (matcher.find()) {
            if (!forms.contains(matcher.group())) {
                forms.add(matcher.group());
            }
        }
        assertTrue(forms.size() >= 2, "CLI 至少披露两个键短形: " + cliText);
        return forms;
    }
}
