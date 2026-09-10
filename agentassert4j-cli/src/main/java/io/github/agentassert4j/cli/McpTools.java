package io.github.agentassert4j.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MCP 工具面 — 既有 CLI 命令的薄壳（零新判定能力）+ record 摄取。
 *
 * <p>适配方式：同包直调命令类，命令实例的 out/err 流字段替换为捕获流、jsonOutput 恒真
 * （命令产出的 stdout JSON 报告行成为工具结果本体）、db 注入 server 绑定值；不经 picocli
 * 参数解析，无全局 System 流替换。读动词（check/diff/re-drive）走 ci 语义——不自动建档、
 * 不收编漂移，治理写只能经变异动词（establish/accept/reject）发生；变异动词的使用要求
 * 写在 manifest description，授权确认由 harness 权限系统执行。</p>
 *
 * <p>manifest 即面向 AI 的文档；工具名与 CLI 动词同词（人类通道与机器通道术语同形）。
 * 工具清单注册序即 tools/list 呈现序（静态、确定性，利于客户端缓存）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpTools {

    private McpTools() {
    }

    /**
     * 构建工具清单（注册序即清单序）。
     *
     * @param db server 绑定的库路径（--db；null 时命令走配置 storage.url）
     */
    static List<McpTool> tools(String db) {
        List<McpTool> tools = new ArrayList<>();
        tools.add(McpTool.of("check", "Project-wide behavior check with zero LLM calls: template drift detection plus per-task chain alignment. " + "Precondition: recorded interactions in the database (starter SDK in-app recording, or the record tool). " + "Runs CI semantics: invocations without baselines are refused with a pointer to establish, and no governance state is written. " + "PASS means no behavioral regression since the baselines; CHANGED means a behavioral difference, reported per task with per-step diffs.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", CliCommands("replay"), args -> runReplay(db, args, true, false)));
        tools.add(McpTool.of("diff", "Behavioral difference for a narrowed scope, same zero-call engine as check: per-step alignment of each task's latest chain " + "against its previous chain, plus template drift, scoped by task request-text prefix and/or invocation selector. " + "CI semantics like check: refuses when the scope holds unbaselined invocations.", "{\"type\":\"object\",\"properties\":{" + "\"task\":{\"type\":\"string\",\"description\":\"Task chain request-text prefix\"}," + "\"invocation\":{\"type\":\"string\",\"description\":\"Invocation selector: business label, invocationKey, unique prefix, or the status display form\"}}," + "\"additionalProperties\":false}", CliCommands("replay"), args -> runReplay(db, args, true, false)));
        tools.add(McpTool.of("report", "Snapshot of recorded invocations and baselines: version tags, pending candidate fingerprints, stability view over repeated runs, " + "and exit-health counts. Read-only. Default output is the status/1 JSON report (always full-project); " + "diff=true switches to the full human inspection view with per-dimension candidate vs baseline diffs.", "{\"type\":\"object\",\"properties\":{" + "\"invocation\":{\"type\":\"string\",\"description\":\"Narrow the view to one invocation: business label, invocationKey prefix, or the status display form (human view only)\"}," + "\"diff\":{\"type\":\"boolean\",\"description\":\"Render the human inspection view including candidate diffs instead of the JSON report\"}}," + "\"additionalProperties\":false}", CliCommands("status"), args -> runCommand(capture -> {
            StatusCommand command = new StatusCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.diff = optionalBoolean(args, "diff");
            command.invocation = optionalString(args, "invocation");
            // parity with `status --diff`（候选差异渲染是人读巡检视图，非 status/1 JSON 字段）
            command.jsonOutput = !command.diff;
            return command;
        })));
        tools.add(McpTool.of("verify", "Acceptance verification against an acceptance pack file (produced by export): pairs pack tasks with locally recorded chains " + "and judges structural fingerprints, the cross-model acceptance path. Read-only, nothing persisted. " + "Use dryRun first to list pairings without verdicts.", "{\"type\":\"object\",\"properties\":{" + "\"pack\":{\"type\":\"string\",\"description\":\"Acceptance pack file path (from export)\"}," + "\"task\":{\"type\":\"string\",\"description\":\"Verify only pack tasks matching this prefix\"}," + "\"dryRun\":{\"type\":\"boolean\",\"description\":\"List pairings only; no verdicts\"}," + "\"report\":{\"type\":\"string\",\"description\":\"Write a markdown verification report (delivery evidence) to this path\"}}," + "\"required\":[\"pack\"],\"additionalProperties\":false}", CliCommands("verify"), args -> {
            // 直调不经 picocli 的 required 校验——必填宾语在适配层就近校验
            String pack = optionalString(args, "pack");
            if (pack == null) {
                return McpToolOutcome.of(2, CliSupport.errorEnvelope(CliErrorCode.E_USAGE, "verify requires a pack file path (produced by export).", "Run export first, then pass its out path as pack.", "export") + "\n", "");
            }
            return runCommand(capture -> {
                VerifyCommand command = new VerifyCommand();
                command.out = capture.out;
                command.err = capture.err;
                command.db = db;
                command.packPath = pack;
                command.task = optionalString(args, "task");
                command.dryRun = optionalBoolean(args, "dryRun");
                command.reportPath = optionalString(args, "report");
                command.jsonOutput = true;
                return command;
            });
        }));
        tools.add(McpTool.of("doctor", "Database health check: identity splits, unlabeled multi-step chains, malformed rule declarations, unestablished points — " + "each finding carries actionable advice. Read-only. Run this first when check refuses or a report looks wrong.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", CliCommands("doctor"), args -> runCommand(capture -> {
            DoctorCommand command = new DoctorCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("graph", "Runtime data-flow inspection: rebuilds the dependency graph from recorded interactions and shows who feeds whom " + "(tool arguments traced back to upstream responses) with cycle detection. Development-time survey; verdicts never consume the graph.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", CliCommands("graph show"), args -> runCommand(capture -> {
            GraphShowCommand command = new GraphShowCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("record", "Report one completed LLM interaction for storage — the entry ticket for non-Java stacks. " + "Send the raw request and response JSON (strings) your stack produced; supports OpenAI-compatible chat completions, Anthropic Messages, and OpenAI Responses wire formats, " + "omitted protocol is auto-detected from the response shape. The server parses them into a stored interaction " + "with derived identity: a declared invocation label wins, otherwise the system prompt template anchors the point. " + "Idempotent: re-sending the same recordId (or the same response id) is stored once and reported as duplicate. " + "Declare invocation and taskKey to group calls into invocations and task chains; check and diff then work on them.", "{\"type\":\"object\",\"properties\":{" + "\"sessionId\":{\"type\":\"string\",\"description\":\"Recording session identity; task chains group within it\"}," + "\"request\":{\"type\":\"string\",\"description\":\"Raw LLM request JSON (chat completions, Anthropic Messages, or Responses shape)\"}," + "\"response\":{\"type\":\"string\",\"description\":\"Raw LLM response JSON the endpoint returned (a 200 body)\"}," + "\"protocol\":{\"type\":\"string\",\"description\":\"Wire protocol: openai-chat, anthropic-messages, or openai-responses; omitted means auto-detect from the response shape\"}," + "\"invocation\":{\"type\":\"string\",\"description\":\"Declared invocation label (identity anchor; wins over template anchoring)\"}," + "\"taskKey\":{\"type\":\"string\",\"description\":\"Declared task key; groups chains across runs (stored in metadata)\"}," + "\"recordId\":{\"type\":\"string\",\"description\":\"Idempotency key; defaults to the response id, then a content hash\"}," + "\"timestamp\":{\"type\":\"integer\",\"description\":\"Epoch milliseconds of the call (defaults to now)\"}," + "\"latencyMs\":{\"type\":\"integer\",\"description\":\"Measured call latency in milliseconds, if known\"}," + "\"metadata\":{\"type\":\"string\",\"description\":\"Additional metadata as a JSON object string\"}}," + "\"required\":[\"sessionId\",\"request\",\"response\"],\"additionalProperties\":false}", CliCommands(), args -> McpRecordIngestion.ingest(db, args)));
        tools.add(McpTool.of("record-show", "Show one stored interaction's full raw request and response by recordId (troubleshooting/forensics). Read-only. The recordId is the one the record tool echoed when saving.", "{\"type\":\"object\",\"properties\":{" + "\"recordId\":{\"type\":\"string\",\"description\":\"Record id to show (echoed by record on save)\"}}," + "\"required\":[\"recordId\"],\"additionalProperties\":false}", CliCommands("record show"), args -> {
            String recordId = optionalString(args, "recordId");
            if (recordId == null) {
                return McpToolOutcome.of(2, CliSupport.errorEnvelope(CliErrorCode.E_USAGE, "record-show requires a recordId (the id the record tool echoed when saving).", "Run record first; its report echoes the recordId.", "record") + "\n", "");
            }
            return runCommand(capture -> {
                RecordShowCommand command = new RecordShowCommand();
                command.out = capture.out;
                command.err = capture.err;
                command.db = db;
                command.recordId = recordId;
                command.jsonOutput = true;
                return command;
            });
        }));
        tools.add(McpTool.of("establish", "Establish baselines for recorded invocations (idempotent, safe to re-run; force rebuilds existing baselines under the current judgment semantics). " + "Governance write: call after a trustworthy recorded run, typically on human instruction. " + "Agents declare themselves with approver like \"agent:<name>\"; the CLI audit command lists such writes.", "{\"type\": \"object\", \"properties\": {\"invocation\": {\"type\": \"string\", \"description\": \"Only this invocation (defaults to all)\"}, \"approver\": {\"type\": \"string\", \"description\": \"Approver identity stamped on the baseline; agents use agent:<name>\"}, \"ref\": {\"type\": \"string\", \"description\": \"Code reference (e.g. a git commit) the baselines correspond to\"}, \"force\": {\"type\": \"boolean\", \"description\": \"Rebuild existing baselines under the current judgment semantics\"}, \"expectedVersion\": {\"type\": \"string\", \"description\": \"Optimistic concurrency guard for force: refuse unless every active baseline version still equals this tag\"}}, \"additionalProperties\": false}", CliCommands("baseline"), args -> runCommand(capture -> {
            BaselineCommand command = new BaselineCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.invocation = optionalString(args, "invocation");
            command.approver = optionalString(args, "approver");
            command.codeRef = optionalString(args, "ref");
            command.force = optionalBoolean(args, "force");
            command.expectedVersion = optionalString(args, "expectedVersion");
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("accept", "Promote a pending candidate fingerprint (landed by check or diff on CHANGED) to the baseline; the previous baseline is archived " + "and restorable via the CLI rollback command. Governance write: call only when the human decides the new behavior is intended. " + "Agents declare themselves with approver like \"agent:<name>\".", "{\"type\": \"object\", \"properties\": {\"invocation\": {\"type\": \"string\", \"description\": \"Invocation holding the candidate (defaults to all pending)\"}, \"approver\": {\"type\": \"string\", \"description\": \"Approver identity; agents use agent:<name>\"}, \"ref\": {\"type\": \"string\", \"description\": \"Code reference (e.g. a git commit) the promoted baseline corresponds to\"}, \"expectedVersion\": {\"type\": \"string\", \"description\": \"Optimistic concurrency guard: refuse unless the active baseline version still equals this tag (see report)\"}}, \"additionalProperties\": false}", CliCommands("accept"), args -> runCommand(capture -> {
            AcceptCommand command = new AcceptCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.invocation = optionalString(args, "invocation");
            command.approver = optionalString(args, "approver");
            command.codeRef = optionalString(args, "ref");
            command.expectedVersion = optionalString(args, "expectedVersion");
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("reject", "Discard a pending candidate fingerprint and keep the current baseline. Governance write: call on the human decision to keep the old behavior. " + "Reverting the prompt change itself is git's job.", "{\"type\": \"object\", \"properties\": {\"invocation\": {\"type\": \"string\", \"description\": \"Invocation holding the candidate (defaults to all pending)\"}, \"expectedVersion\": {\"type\": \"string\", \"description\": \"Optimistic concurrency guard: refuse unless the active baseline version still equals this tag (see report)\"}}, \"additionalProperties\": false}", CliCommands("reject"), args -> runCommand(capture -> {
            RejectCommand command = new RejectCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.invocation = optionalString(args, "invocation");
            command.expectedVersion = optionalString(args, "expectedVersion");
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("rollback", "Restore the active baseline to a previously archived version (see the archived column in report). Governance write: call when the human decides to roll back; " + "restoring stamps no new approval trail — the audit command remains the version-history record. Requires invocation and version.", "{\"type\": \"object\", \"properties\": {\"invocation\": {\"type\": \"string\", \"description\": \"Target invocation: business label, invocationKey, or unique prefix\"}, \"version\": {\"type\": \"string\", \"description\": \"Archived version tag to restore (e.g. v1)\"}, \"expectedVersion\": {\"type\": \"string\", \"description\": \"Optimistic concurrency guard: refuse unless the active baseline version still equals this tag (see report)\"}}, \"required\": [\"invocation\", \"version\"], \"additionalProperties\": false}", CliCommands("rollback"), args -> {
            String invocation = optionalString(args, "invocation");
            String version = optionalString(args, "version");
            if (invocation == null || version == null) {
                return McpToolOutcome.of(2, CliSupport.errorEnvelope(CliErrorCode.E_USAGE, "rollback requires invocation and version (see the archived column in report).", "Run report first, then pass the invocation and the archived version tag to restore.", "report") + "\n", "");
            }
            return runCommand(capture -> {
                RollbackCommand command = new RollbackCommand();
                command.out = capture.out;
                command.err = capture.err;
                command.db = db;
                command.invocation = invocation;
                command.version = version;
            command.expectedVersion = optionalString(args, "expectedVersion");
                command.jsonOutput = true;
                return command;
            });
        }));
        tools.add(McpTool.of("audit", "List agent-driven governance writes (approver marked agent:*) for human review: active baselines and archived versions. Read-only; use it to reconcile governance writes after establish/accept/reject.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", CliCommands("audit"), args -> runCommand(capture -> {
            AuditCommand command = new AuditCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("rules", "Show the built-in constraint behavior catalog and rules-file examples (read-only). The rules file declares per-invocation output constraints (behaviors, required/forbidden keywords, regex) and per-task discipline (required steps/order/counts); the file is looked up next to agentassert4j.json first, then the working directory, then ~/.agentassert4j/.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", CliCommands("rules"), args -> runCommand(capture -> {
            RulesCommand command = new RulesCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.jsonOutput = true;
            return command;
        })));
        tools.add(McpTool.of("member-check", "Member determination: the latest chain of each task is checked against its most recent chains (bounded window 5) — matching any of them passes. " + "Use it to judge whether a new execution still belongs to the known behavior cluster under fluctuating real model behavior. Read-only judgment: no governance writes.", "{\"type\":\"object\",\"properties\":{" + "\"task\":{\"type\":\"string\",\"description\":\"Task chain request-text prefix\"}," + "\"invocation\":{\"type\":\"string\",\"description\":\"Invocation selector\"}}," + "\"additionalProperties\":false}", CliCommands("replay"), args -> runReplay(db, args, true, false, true)));
        tools.add(McpTool.of("re-drive", "Controlled re-drive (long-running, spends real LLM calls): re-runs recorded inputs through each point's latest archived template " + "to confirm drift with fresh evidence. Set a generous client timeout; pass maxTotalCalls/maxTotalTokens budgets; " + "prefer dryRun first for a cost estimate. CI semantics: no governance writes.", "{\"type\":\"object\",\"properties\":{" + "\"task\":{\"type\":\"string\",\"description\":\"Task chain request-text prefix\"}," + "\"invocation\":{\"type\":\"string\",\"description\":\"Invocation selector\"}," + "\"fullChain\":{\"type\":\"boolean\",\"description\":\"Re-drive every record in scope, not only drift points\"}," + "\"maxTotalCalls\":{\"type\":\"integer\",\"description\":\"Budget cap on real re-drive calls\"}," + "\"maxTotalTokens\":{\"type\":\"integer\",\"description\":\"Budget cap on total re-drive tokens\"}," + "\"dryRun\":{\"type\":\"boolean\",\"description\":\"Read-only cost estimate; no calls, no writes\"}}," + "\"additionalProperties\":false}", CliCommands("replay"), args -> runReplay(db, args, true, true)));
        tools.add(McpTool.of("export", "Write an acceptance pack (JSON file) from current baselines for delivery verification with verify — the cross-model or offline acceptance path. " + "Optional ref stamps the code reference (e.g. a git commit) the pack corresponds to; declared, not verified.", "{\"type\":\"object\",\"properties\":{" + "\"task\":{\"type\":\"string\",\"description\":\"Export only task chains matching this request-text prefix\"}," + "\"out\":{\"type\":\"string\",\"description\":\"Output file path (default acceptance-pack.json)\"}," + "\"includeSamples\":{\"type\":\"boolean\",\"description\":\"Attach masked per-step input/output samples\"}," + "\"ref\":{\"type\":\"string\",\"description\":\"Code reference recorded in the pack metadata\"}}," + "\"additionalProperties\":false}", CliCommands("baseline export"), args -> runCommand(capture -> {
            BaselineExportCommand command = new BaselineExportCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.task = optionalString(args, "task");
            String out = optionalString(args, "out");
            // 直调不经 picocli，注解 defaultValue 不生效——缺省值由适配层承接
            command.outPath = out != null ? out : "acceptance-pack.json";
            command.includeSamples = optionalBoolean(args, "includeSamples");
            command.codeRef = optionalString(args, "ref");
            command.jsonOutput = true;
            return command;
        })));
        return tools;
    }

    /**
     * replay 形工具的共用适配：缩域选择器 + ci 语义（读动词零治理写）；re-drive 模式叠加。
     */
    /** 工具封装的 CLI 命令路径声明（面一致性真源，见 McpTool.cliCommands）。 */
    private static List<String> CliCommands(String... paths) {
        return Arrays.asList(paths);
    }

    private static McpToolOutcome runReplay(String db, Map<String, Object> args, boolean ciMode, boolean reDrive) {
        return runReplay(db, args, ciMode, reDrive, false);
    }

    private static McpToolOutcome runReplay(String db, Map<String, Object> args, boolean ciMode, boolean reDrive, boolean memberCheck) {
        return runCommand(capture -> {
            ReplayCommand command = new ReplayCommand();
            command.out = capture.out;
            command.err = capture.err;
            command.db = db;
            command.task = optionalString(args, "task");
            command.invocation = optionalString(args, "invocation");
            command.ciMode = ciMode;
            command.memberCheck = memberCheck;
            command.reDrive = reDrive;
            if (reDrive) {
                command.fullChain = optionalBoolean(args, "fullChain");
                command.maxTotalCalls = optionalInteger(args, "maxTotalCalls");
                command.maxTotalTokens = optionalInteger(args, "maxTotalTokens");
                command.dryRun = optionalBoolean(args, "dryRun");
            }
            command.jsonOutput = true;
            return command;
        });
    }

    /**
     * 命令配置器 — 在捕获流就绪后装配命令实例（流字段注入必须在 call 之前完成）。
     */
    private interface CommandConfigurator {
        Callable<Integer> configure(Capture capture);
    }

    private static McpToolOutcome runCommand(CommandConfigurator configurator) {
        Capture capture = new Capture();
        Callable<Integer> command = configurator.configure(capture);
        try {
            int exit = command.call();
            return McpToolOutcome.of(exit, capture.stdoutText(), capture.stderrText());
        } catch (Exception e) {
            // 命令自身的失败路径已以 exit 2 + 包络返回；到达此处 = 命令面未预期的崩溃
            return McpToolOutcome.of(2, CliSupport.errorEnvelope(CliErrorCode.E_ENV, "tool failed: " + CliSupport.describe(e), "Fix the reported problem, then retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor") + "\n", capture.stderrText());
        }
    }

    /**
     * 单次工具执行的捕获流对——命令产出全部落在内存缓冲，与协议通道物理隔离。
     */
    private static final class Capture {
        private final ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        final PrintStream out;
        final PrintStream err;

        Capture() {
            try {
                this.out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8.name());
                this.err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                // JVM 规范强制支持 UTF-8，此分支不可达
                throw new IllegalStateException("UTF-8 charset must be supported", e);
            }
        }

        String stdoutText() {
            return decode(outBuffer);
        }

        String stderrText() {
            return decode(errBuffer);
        }

        private static String decode(ByteArrayOutputStream buffer) {
            try {
                return buffer.toString(StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                return buffer.toString();
            }
        }
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static boolean optionalBoolean(Map<String, Object> args, String key) {
        return Boolean.TRUE.equals(args.get(key));
    }

    private static Integer optionalInteger(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }
}
