package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 任务链视图 — 从录制记录确定性派生任务链（派生视图零 schema）。
 *
 * <p>会话内按规范序（timestamp→seq→recordId）行走：记录 userInput 非 blank
 * 则更新当前请求文本并归入链，blank（典型 tool 结果轮）归入当前链，
 * 会话开头无当前请求的记录不属于任何任务链。记录 metadata 含非 blank
 * {@code taskKey} 时以声明值为请求文本（声明优先于派生）。</p>
 *
 * <p>派生是纯函数：同一批记录永远得到同一批链——这是「任务键 = (会话, 请求文本)」
 * 配对语义的前置性质。任务链不落库、可随时全量重建（与依赖图同一 schema 哲学）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public final class TaskChainView {

    /**
     * metadata 中显式任务键的保留字段名（声明优先于 userInput 派生）
     */
    public static final String DECLARED_TASK_KEY = "taskKey";

    private static final Comparator<InteractionRecord> CANONICAL = Comparator.comparing(InteractionRecord::getTimestamp).thenComparing(InteractionRecord::getSeq).thenComparing(InteractionRecord::getRecordId);

    private TaskChainView() {
    }

    /**
     * 全库任务链：所有会话的链合并且按链首时间升序（跨会话配对与最新链选择共用本口径）。
     */
    public static List<TaskChain> resolveAll(InteractionQueryStore repository) {
        List<TaskChain> all = new ArrayList<>();
        for (String sessionId : repository.findAllSessionIds()) {
            all.addAll(resolveSession(sessionId, repository.findBySessionId(sessionId)));
        }
        all.sort(Comparator.comparingLong(TaskChain::firstTimestamp));
        return all;
    }

    /**
     * 单会话任务链派生。传入记录不必有序，内部按规范序重排。
     */
    public static List<TaskChain> resolveSession(String sessionId, List<InteractionRecord> records) {
        List<InteractionRecord> ordered = new ArrayList<>(records);
        ordered.sort(CANONICAL);

        List<TaskChain> chains = new ArrayList<>();
        TaskChain current = null;
        for (InteractionRecord record : ordered) {
            String declared = declaredTaskKey(record);
            String text = declared != null ? declared : blankToNull(record.getUserInput());
            if (text != null) {
                // 请求文本出现：开启新链（同会话同文本重复提问按字面键并入同链——
                // 由既有链延续而非新开，链内按规范序自然含两次执行）
                if (current == null || !current.getRequestText().equals(text) || current.isDeclared() != (declared != null)) {
                    current = new TaskChain();
                    current.setSessionId(sessionId);
                    current.setRequestText(text);
                    current.setDeclared(declared != null);
                    current.setRecords(new ArrayList<>());
                    chains.add(current);
                }
            }
            if (current != null) {
                current.getRecords().add(record);
            }
            // current == null：会话开头的无请求记录（纯 tool 起始）不属于任何任务链
        }
        return chains;
    }

    /**
     * 声明任务键：metadata JSON 的 taskKey 字段，非 blank 才有效。
     * metadata 解析失败按未声明处理（派生退化不中断）。
     */
    static String declaredTaskKey(InteractionRecord record) {
        String metadata = record.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            Object parsed = RecursiveJsonParser.parse(metadata);
            if (parsed instanceof Map) {
                Object key = ((Map<?, ?>) parsed).get(DECLARED_TASK_KEY);
                if (key != null) {
                    String text = String.valueOf(key).trim();
                    return text.isEmpty() ? null : text;
                }
            }
        } catch (RuntimeException ignored) {
            // 损坏 metadata 按未声明处理
        }
        return null;
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
