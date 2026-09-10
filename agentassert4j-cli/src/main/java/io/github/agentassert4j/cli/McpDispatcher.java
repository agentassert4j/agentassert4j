package io.github.agentassert4j.cli;

import io.github.agentassert4j.util.RecursiveJsonParser;

import java.io.PrintStream;
import java.util.*;

/**
 * MCP 分发器 — JSON-RPC 校验、握手状态机、方法路由与工具结果组装。
 *
 * <p>方法面封闭五方法（initialize / notifications/initialized / tools/list / tools/call /
 * ping）；未知请求回 -32601，未知通知静默忽略（对通知回错即协议违规）。错误分类法：
 * 请求结构级缺陷（含未知工具名）走 JSON-RPC 协议错误码；已知工具的参数语义错误与命令
 * exit 2 走 isError:true 结果体，结构化本体复用 agentassert4j.error/1 包络。工具结果
 * 双形态：content 文本块（报告行原样，证据预算内）+ structuredContent JSON 对象
 * （reports 数组 / error 包络）。exit 0/1（含 CHANGED 判定）不是错误——判定语义由
 * 报告承载。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpDispatcher implements StdioTransport.MessageHandler {

    /**
     * 声明支持的协议版本集（握手式方言）：initialize 请求版本在集内则原样回显，
     * 否则回 DEFAULT_PROTOCOL_VERSION。2026-07-28 修订改为逐请求 _meta 版本声明
     * （非握手），不在本 server 支持集内——客户端若只支持该方言会自行断开。
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = new HashSet<>(Arrays.asList("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"));
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";

    private static final String SERVER_NAME = "agentassert4j";
    private static final String INSTRUCTIONS = "Behavior regression for AI agents: record interactions first (starter SDK in-app, or the record tool), " + "establish baselines, then check/diff after prompt or model changes. " + "PASS means no behavioral change since the baseline; CHANGED findings land candidate fingerprints awaiting accept/reject. " + "Mutation tools (establish, accept, reject) write governance state: call them on human instruction " + "and declare agent identity via approver \"agent:<name>\".";

    /**
     * 文本块证据预算（字符）：超出截断并标注——AI 可缩域重取；structuredContent 恒全量。
     */
    static final int TEXT_BUDGET_CHARS = 100_000;

    private final List<McpTool> tools;
    private final PrintStream diag;
    private boolean initialized;

    McpDispatcher(List<McpTool> tools, PrintStream diag) {
        this.tools = tools;
        this.diag = diag;
    }

    @Override
    public String handle(String line) {
        long startNanos = System.nanoTime();
        String response = dispatch(line);
        if (diag != null && response != null) {
            diag.println("mcp: " + methodOf(line) + " -> " + (response.length() > 80 ? response.substring(0, 80) + "..." : response) + " (" + (System.nanoTime() - startNanos) / 1_000_000L + " ms)");
        }
        return response;
    }

    @Override
    public String malformedResponse(String reason) {
        return errorResponse(null, -32600, "Invalid request: " + reason);
    }

    private String dispatch(String line) {
        Object parsed = RecursiveJsonParser.parse(line);
        if (parsed == null) {
            // 解析器退化契约：解析失败与字面 null 同返 null。字面 null 是合法 JSON 值
            //（消息级非法 → invalid request）；其余不可解析文本才是 parse error
            return "null".equals(line.trim()) ? errorResponse(null, -32600, "Invalid request: message must be a JSON object") : errorResponse(null, -32700, "Parse error: message is not valid JSON");
        }
        if (!(parsed instanceof Map)) {
            return errorResponse(null, -32600, "Invalid request: message must be a JSON object");
        }
        Map<?, ?> message = (Map<?, ?>) parsed;
        boolean hasId = message.containsKey("id");
        Object id = message.get("id");

        if (!"2.0".equals(message.get("jsonrpc"))) {
            return errorResponse(echoableId(hasId, id), -32600, "Invalid request: jsonrpc must be \"2.0\"");
        }
        Object method = message.get("method");
        if (!(method instanceof String) || ((String) method).isEmpty()) {
            if (!hasId && (message.containsKey("result") || message.containsKey("error"))) {
                // 客户端误发的响应帧：无 method 无从路由，按通知静默忽略
                return null;
            }
            return errorResponse(echoableId(hasId, id), -32600, "Invalid request: method must be a non-empty string");
        }
        if (hasId && (id instanceof Map || id instanceof List)) {
            return errorResponse(null, -32600, "Invalid request: id must be a string, number, or null");
        }
        if (!hasId) {
            // 通知：一律静默（未知通知不回错；notifications/cancelled 收到即忽略，v1 无可取消语义）
            return null;
        }
        return request((String) method, message.get("params"), id);
    }

    private String request(String method, Object params, Object id) {
        if (!initialized) {
            if ("initialize".equals(method)) {
                return initialize(params, id);
            }
            if ("ping".equals(method)) {
                return resultJson(id, "{}");
            }
            return errorResponse(id, -32002, "Server not initialized: send initialize first");
        }
        if ("initialize".equals(method)) {
            return errorResponse(id, -32600, "Invalid request: already initialized");
        }
        if ("ping".equals(method)) {
            return resultJson(id, "{}");
        }
        if ("tools/list".equals(method)) {
            return toolsList(id);
        }
        if ("tools/call".equals(method)) {
            return toolsCall(params, id);
        }
        return errorResponse(id, -32601, "Method not found: " + method);
    }

    private String initialize(Object params, Object id) {
        String requested = params instanceof Map && ((Map<?, ?>) params).get("protocolVersion") instanceof String ? (String) ((Map<?, ?>) params).get("protocolVersion") : null;
        String negotiated = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL_VERSION;
        initialized = true;
        StringBuilder sb = new StringBuilder("{\"protocolVersion\":\"").append(negotiated).append('"');
        sb.append(",\"capabilities\":{\"tools\":{\"listChanged\":false}}");
        sb.append(",\"serverInfo\":{\"name\":\"").append(SERVER_NAME).append("\",\"version\":\"").append(RecursiveJsonParser.escape(AgentAssert4jCli.FRAMEWORK_VERSION)).append("\"}");
        sb.append(",\"instructions\":\"").append(RecursiveJsonParser.escape(INSTRUCTIONS)).append("\"}");
        return resultJson(id, sb.toString());
    }

    /**
     * 工具清单：注册序呈现（静态确定性）；inputSchema 原样拼接。分页不支持——
     * cursor 参数忽略、不返回 nextCursor（工具面恒 ≤ 12 个）。
     */
    private String toolsList(Object id) {
        StringBuilder sb = new StringBuilder("{\"tools\":[");
        boolean first = true;
        for (McpTool tool : tools) {
            if (!first) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(RecursiveJsonParser.escape(tool.name)).append('"');
            sb.append(",\"description\":\"").append(RecursiveJsonParser.escape(tool.description)).append('"');
            sb.append(",\"inputSchema\":").append(tool.inputSchemaJson).append('}');
            first = false;
        }
        return resultJson(id, sb.append("]}").toString());
    }

    private String toolsCall(Object params, Object id) {
        Map<?, ?> callParams = params instanceof Map ? (Map<?, ?>) params : new LinkedHashMap<String, Object>();
        Object name = callParams.get("name");
        if (!(name instanceof String) || ((String) name).isEmpty()) {
            return errorResponse(id, -32602, "Invalid params: tools/call requires a tool name string");
        }
        Object arguments = callParams.get("arguments");
        if (arguments != null && !(arguments instanceof Map)) {
            return errorResponse(id, -32602, "Invalid params: arguments must be an object");
        }
        McpTool tool = findTool((String) name);
        if (tool == null) {
            return errorResponse(id, -32602, "Unknown tool: " + name);
        }
        McpToolOutcome outcome;
        try {
            outcome = tool.handler.invoke(castArguments(arguments));
        } catch (RuntimeException e) {
            outcome = McpToolOutcome.of(2, CliSupport.errorEnvelope(CliErrorCode.E_ENV, "tool crashed: " + CliSupport.describe(e), "Fix the reported problem, then retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor") + "\n", "");
        }
        return resultJson(id, toolResultJson(outcome));
    }

    /**
     * 工具结果双形态组装：content 文本块 = stdout 报告行（失败态追加 stderr 诊断），
     * 证据预算内截断；structuredContent = 失败态取 error/1 包络对象、成功态取
     * {"reports":[...]}（仅收可解析为 JSON 对象的行，无可解析行则整个省略）。
     */
    private String toolResultJson(McpToolOutcome outcome) {
        boolean isError = outcome.failed();
        String stdout = McpVerbs.channelize(outcome.stdout);
        String stderr = McpVerbs.channelize(outcome.stderr);
        StringBuilder text = new StringBuilder(stdout);
        if (!stderr.isEmpty()) {
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
            text.append("stderr:\n").append(stderr);
        }
        String textValue = text.toString();
        if (textValue.length() > TEXT_BUDGET_CHARS) {
            textValue = textValue.substring(0, TEXT_BUDGET_CHARS) + "\n… (truncated at " + TEXT_BUDGET_CHARS + " chars; narrow the scope, or read structuredContent)";
        }
        StringBuilder sb = new StringBuilder("{\"content\":[{\"type\":\"text\",\"text\":\"");
        sb.append(RecursiveJsonParser.escape(textValue));
        sb.append("\"}]");
        String structured = structuredContent(stdout, isError);
        if (structured != null) {
            sb.append(",\"structuredContent\":").append(structured);
        }
        if (isError) {
            sb.append(",\"isError\":true");
        }
        return sb.append('}').toString();
    }

    /**
     * structuredContent 取值：失败态 = stdout 中最后一个 error/1 包络对象（兜底首个
     * 可解析对象）；成功态 = 全部可解析报告行组成的 reports 数组（空则省略字段）。
     * 报告行逐行验证后才嵌入——半行或非 JSON 文本绝不进结构化通道。
     */
    private String structuredContent(String stdout, boolean isError) {
        Map<String, Object> envelope = null;
        Map<String, Object> firstObject = null;
        StringBuilder reports = new StringBuilder();
        int reportCount = 0;
        for (String line : stdout.split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            Object parsed;
            try {
                parsed = RecursiveJsonParser.parse(line);
            } catch (RuntimeException e) {
                continue;
            }
            if (!(parsed instanceof Map)) {
                continue;
            }
            Map<?, ?> object = (Map<?, ?>) parsed;
            if (isError && "agentassert4j.error/1".equals(object.get("schema"))) {
                envelope = castArguments(object);
            }
            if (firstObject == null) {
                firstObject = castArguments(object);
            }
            if (!isError) {
                if (reportCount > 0) {
                    reports.append(',');
                }
                reports.append(line);
                reportCount++;
            }
        }
        if (isError) {
            Map<String, Object> chosen = envelope != null ? envelope : firstObject;
            return chosen != null ? RecursiveJsonParser.serialize(chosen) : null;
        }
        return reportCount > 0 ? "{\"reports\":[" + reports + "]}" : null;
    }

    private McpTool findTool(String name) {
        for (McpTool tool : tools) {
            if (tool.name.equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private static String resultJson(Object id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(id) + ",\"result\":" + resultJson + "}";
    }

    private static String errorResponse(Object id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(id) + ",\"error\":{\"code\":" + code + ",\"message\":\"" + RecursiveJsonParser.escape(message) + "\"}}";
    }

    /**
     * id 回显保真：字符串转义回显；数字（Long/Double）与 Boolean 按字面回显；
     * null（含不可回显形态）以 null 出境。id 只能来自解析结果的三种标量。
     */
    private static String idLiteral(Object id) {
        if (id instanceof String) {
            return "\"" + RecursiveJsonParser.escape((String) id) + "\"";
        }
        if (id instanceof Long || id instanceof Double || id instanceof Boolean) {
            return id.toString();
        }
        return "null";
    }

    /**
     * 消息级校验失败时的 id 回显：id 形态可回显（标量或 null）才带出境，否则 null——
     * 对未知形态的 id 猜测回显只会制造客户端配对错乱。
     */
    private static Object echoableId(boolean hasId, Object id) {
        if (!hasId || id instanceof Map || id instanceof List) {
            return null;
        }
        return id;
    }

    /**
     * 诊断行的 method 名（解析失败/无 method 时给占位标记）。
     */
    private static String methodOf(String line) {
        Object parsed;
        try {
            parsed = RecursiveJsonParser.parse(line);
        } catch (RuntimeException e) {
            return "(unparseable)";
        }
        if (parsed instanceof Map && ((Map<?, ?>) parsed).get("method") instanceof String) {
            return (String) ((Map<?, ?>) parsed).get("method");
        }
        return "(no method)";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArguments(Object arguments) {
        if (arguments instanceof Map) {
            return (Map<String, Object>) arguments;
        }
        return new LinkedHashMap<String, Object>();
    }
}
