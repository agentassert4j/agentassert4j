package io.github.agentassert4j.model;

import java.util.List;

/**
 * 任务链 — 会话内一次任务执行的全部记录（派生视图，零 schema）。
 *
 * <p>由 TaskChainView 从录制记录确定性派生：链 = (会话, 请求文本)，
 * 链内记录序即任务内步骤序。请求文本可来自记录 userInput 或
 * metadata 的显式声明（声明优先）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class TaskChain {

    private String sessionId;
    /**
     * 请求文本（已 trim；声明 taskKey 优先于 userInput）
     */
    private String requestText;
    /**
     * 请求文本是否来自显式声明（metadata taskKey）
     */
    private boolean declared;
    /**
     * 链内记录（规范序 = 任务内步骤序）
     */
    private List<InteractionRecord> records;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestText() {
        return requestText;
    }

    public void setRequestText(String requestText) {
        this.requestText = requestText;
    }

    public boolean isDeclared() {
        return declared;
    }

    public void setDeclared(boolean declared) {
        this.declared = declared;
    }

    public List<InteractionRecord> getRecords() {
        return records;
    }

    public void setRecords(List<InteractionRecord> records) {
        this.records = records;
    }

    /**
     * 链首记录时间戳（跨会话配对时「最新链」的排序依据）；空链返回 Long.MAX_VALUE
     */
    public long firstTimestamp() {
        return records == null || records.isEmpty() ? Long.MAX_VALUE : records.get(0).getTimestamp();
    }
}
