package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * record show 命令 — 按 recordId 回显一条交互的 raw wire 双列与关键元数据。
 *
 * <p>排障/取证面：报告与指纹只承载结构结论，正文原文只存在 raw 双列里。
 * wire 摄取的记录双列恒在；SDK 捕获的记录可能无 raw（ChatModel 层不含 wire），
 * 此时如实标注缺失。超长正文全量输出——本命令的职责就是给全文。</p>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
@Command(name = "show", description = "Show one stored interaction's raw request and response (troubleshooting/forensics)", mixinStandardHelpOptions = true)
public class RecordShowCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--record-id"}, required = true, description = "Record id to show (the record tool echoes it when saving)")
    String recordId;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout (agentassert4j.record-view/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            InteractionRecord record = repository.findByRecordId(recordId);
            if (record == null) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, "No recorded interaction with recordId: " + recordId, "recordIds are echoed by the record tool when saving; run `agentassert4j status` to see what exists.", "agentassert4j status");
            }
            if (jsonOutput) {
                out.println(recordViewJson(record));
            } else {
                printHuman(record);
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "record show failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private void printHuman(InteractionRecord record) {
        out.println("Record " + record.getRecordId() + " (session " + record.getSessionId() + ")");
        if (record.getInvocationId() != null || record.getInvocationKey() != null) {
            out.println("  Invocation: " + CliSupport.visibleText(record.getInvocationId()) + " -> " + CliSupport.displayKey(record.getInvocationKey() != null ? record.getInvocationKey() : ""));
        }
        if (record.getApiProtocol() != null) {
            out.println("  Protocol: " + record.getApiProtocol());
        }
        out.println("  Model: " + CliSupport.visibleText(record.getModel()) + (record.getServedModel() != null ? " (served " + record.getServedModel() + ")" : ""));
        out.println("  Turn " + record.getTurnIndex() + " | Tokens: " + record.getInputTokens() + " in / " + record.getOutputTokens() + " out");
        printRaw(record.getModelRequestRaw(), "request");
        printRaw(record.getModelResponseRaw(), "response");
    }

    private void printRaw(String raw, String label) {
        out.println("  -- " + label + " (raw) --");
        if (raw == null || raw.isEmpty()) {
            out.println("  (no raw " + label + " stored -- this interaction was captured without wire payloads)");
        } else {
            out.println("  " + raw);
        }
    }

    private String recordViewJson(InteractionRecord record) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.record-view/1\",\"recordId\":\"").append(RecursiveJsonParser.escape(record.getRecordId())).append('"');
        sb.append(",\"sessionId\":\"").append(RecursiveJsonParser.escape(record.getSessionId())).append('"');
        if (record.getInvocationId() != null) {
            sb.append(",\"invocationId\":\"").append(RecursiveJsonParser.escape(record.getInvocationId())).append('"');
        }
        if (record.getInvocationKey() != null) {
            sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(record.getInvocationKey())).append('"');
        }
        if (record.getApiProtocol() != null) {
            sb.append(",\"protocol\":\"").append(RecursiveJsonParser.escape(record.getApiProtocol())).append('"');
        }
        if (record.getModel() != null) {
            sb.append(",\"model\":\"").append(RecursiveJsonParser.escape(record.getModel())).append('"');
        }
        if (record.getServedModel() != null) {
            sb.append(",\"servedModel\":\"").append(RecursiveJsonParser.escape(record.getServedModel())).append('"');
        }
        sb.append(",\"turnIndex\":").append(record.getTurnIndex());
        sb.append(",\"timestamp\":").append(record.getTimestamp());
        sb.append(",\"inputTokens\":").append(record.getInputTokens());
        sb.append(",\"outputTokens\":").append(record.getOutputTokens());
        sb.append(",\"hasToolCalls\":").append(record.isHasToolCalls());
        appendRawField(sb, "modelRequestRaw", record.getModelRequestRaw());
        appendRawField(sb, "modelResponseRaw", record.getModelResponseRaw());
        return sb.append('}').toString();
    }

    private void appendRawField(StringBuilder sb, String name, String raw) {
        sb.append(",\"").append(name).append("\":");
        if (raw == null) {
            sb.append("null");
        } else {
            sb.append('"').append(RecursiveJsonParser.escape(raw)).append('"');
        }
    }
}
