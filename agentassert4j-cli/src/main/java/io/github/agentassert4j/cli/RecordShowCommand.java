package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.RedriveMarkerUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * record show 命令 — 按寻址族回显一条交互的 raw wire 双列与关键元数据。
 *
 * <p>排障/取证用途：报告与指纹只承载结构结论，正文原文只存在 raw 双列里。
 * wire 摄取的记录双列恒在；SDK 捕获的记录可能无 raw（ChatModel 层不含 wire），
 * 此时如实标注缺失。超长正文全量输出——本命令的职责就是给全文。</p>
 *
 * <p>寻址族三种形态恰好命中一条记录：{@code --record-id} 精确直达；
 * {@code --session <id> [--index N|--latest]} 按会话规范序（时间、序号、
 * 记录 ID）定位；{@code --invocation <sel> --latest} 按统一调用点选择器解析
 * 后取该键最新记录。会话/调用点恰有一条记录时可省略定位项；多条时必须
 * 显式定位，报错信息携带限量记录清单供挑选。重驱观测记录按存储原样回显
 * （取证要看见仪器的观测，含标记行）。</p>
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

    // 必填项校验放 call() 而非 picocli required=true：picocli 原生缺参报错没有
    // error/1 包络，机器消费方无法按 hints/nextAction 自助续行
    @Option(names = {"--record-id"}, description = "Record id to show (the record tool echoes it when saving)")
    String recordId;

    @Option(names = {"--session"}, description = "Show a record from this session (position picked by --index/--latest)")
    String session;

    @Option(names = {"--invocation"}, description = "Show the latest record of this invocation: business label, invocationKey, or a unique prefix")
    String invocation;

    @Option(names = {"--index"}, description = "1-based position in the session's canonical record order (with --session)")
    Integer index;

    @Option(names = {"--latest"}, description = "Show the newest record of the session/invocation scope")
    boolean latest;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout (agentassert4j.record-view/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        int addressForms = (recordId != null && !recordId.trim().isEmpty() ? 1 : 0) + (session != null && !session.trim().isEmpty() ? 1 : 0) + (invocation != null && !invocation.trim().isEmpty() ? 1 : 0);
        if (addressForms != 1) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, addressForms == 0 ? "record show requires exactly one of --record-id, --session, or --invocation." : "record show takes only one of --record-id, --session, or --invocation.", "Pass --record-id for a direct lookup; --session with --latest/--index N; or --invocation with --latest.", "agentassert4j record show");
        }
        if (recordId != null && !recordId.trim().isEmpty()) {
            if (latest || index != null) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--latest/--index position within --session or --invocation; --record-id needs neither.", "Drop the positioning flag: --record-id already resolves to exactly one record.", "agentassert4j record show");
            }
        } else {
            if (latest && index != null) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "Pick one of --latest or --index, not both.", "The two positioning flags resolve to the same single record; pass the one you mean.", "agentassert4j record show");
            }
            if (index != null && session == null) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--index positions within a session; pass --session too.", "Index N is the 1-based position in the session's canonical record order.", "agentassert4j record show");
            }
            if (index != null && index < 1) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--index is 1-based; pass a value >= 1.", "Index N selects the N-th record of the session in canonical order (timestamp, seq, record id).", "agentassert4j record show");
            }
        }
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, err);
            InteractionRecord record = resolve(repository);
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

    /**
     * 寻址族解析——三种形态收敛为恰一条记录，多候选与零命中的差异在这里
     * 转成带限量清单的 E-NO-DATA/E-USAGE 包络。
     */
    private InteractionRecord resolve(StorageRepository repository) {
        if (recordId != null && !recordId.trim().isEmpty()) {
            InteractionRecord record = repository.findByRecordId(recordId.trim());
            if (record == null) {
                throw new CliFailureException(CliErrorCode.E_NO_DATA, "No recorded interaction with recordId: " + recordId.trim(), "recordIds are echoed by the record tool when saving; run `agentassert4j status` to see what exists.", "agentassert4j status");
            }
            return record;
        }
        if (session != null && !session.trim().isEmpty()) {
            List<InteractionRecord> records = repository.findBySessionId(session.trim());
            if (records.isEmpty()) {
                throw new CliFailureException(CliErrorCode.E_NO_DATA, "No recorded interactions in session: " + session.trim(), "Run `agentassert4j status` to see recorded sessions; recordIds are echoed by the record tool when saving.", "agentassert4j status");
            }
            if (latest) {
                return records.get(records.size() - 1);
            }
            if (index != null) {
                if (index > records.size()) {
                    throw new CliFailureException(CliErrorCode.E_NO_DATA, "Session " + session.trim() + " holds " + CliSupport.plural(records.size(), "record") + "; --index must be 1.." + records.size() + ".", "Retry with a position inside the session, or pass --latest for the newest record.", "agentassert4j record show");
                }
                return records.get(index - 1);
            }
            if (records.size() == 1) {
                return records.get(0);
            }
            throw new CliFailureException(CliErrorCode.E_USAGE, "Session " + session.trim() + " holds " + CliSupport.plural(records.size(), "record") + "; pick one: " + recordListing(records), "Retry with --latest (newest record) or --index N (1-based position).", "agentassert4j record show");
        }
        String invocationKey = CliSupport.resolveInvocationKeyTarget(repository, invocation);
        List<InteractionRecord> records = repository.findByInvocationKey(invocationKey);
        if (records.isEmpty()) {
            throw new CliFailureException(CliErrorCode.E_NO_DATA, "No recorded interactions under " + CliSupport.displayKey(invocationKey) + ".", "Run `agentassert4j status` to see recorded invocations.", "agentassert4j status");
        }
        if (latest || records.size() == 1) {
            return records.get(records.size() - 1);
        }
        throw new CliFailureException(CliErrorCode.E_USAGE, "Invocation " + CliSupport.displayKey(invocationKey) + " holds " + CliSupport.plural(records.size(), "record") + "; pass --latest to show the newest.", "The canonical order is timestamp, seq, record id; --latest resolves to the newest record.", "agentassert4j record show");
    }

    /**
     * 多候选时的限量清单（最多 8 条 + 收尾计数）——操作者拿到记录 id 才能
     * 下钻，否则只能在报错里猜。
     */
    private static String recordListing(List<InteractionRecord> records) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(records.size(), 8);
        for (int i = 0; i < shown; i++) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            InteractionRecord record = records.get(i);
            sb.append("#").append(i + 1).append(" ").append(record.getRecordId()).append(" (turn ").append(record.getTurnIndex()).append(")");
        }
        if (records.size() > shown) {
            sb.append("; … and ").append(CliSupport.plural(records.size() - shown, "more record")).append(" (--index 1..").append(records.size()).append(")");
        }
        return sb.toString();
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
        if (RedriveMarkerUtil.isRedriveObservation(record)) {
            out.println("  Re-drive observation (metadata: " + record.getMetadata() + ")");
        }
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
        StringBuilder sb = new StringBuilder("{\"schema\":\"" + ReportSchemas.RECORD_VIEW + "\",\"recordId\":\"").append(RecursiveJsonParser.escape(record.getRecordId())).append('"');
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
        if (record.getLatencyMs() != 0L) {
            sb.append(",\"latencyMs\":").append(record.getLatencyMs());
        }
        if (record.getMetadata() != null) {
            sb.append(",\"metadata\":").append(record.getMetadata());
        }
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
