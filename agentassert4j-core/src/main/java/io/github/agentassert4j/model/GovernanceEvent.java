package io.github.agentassert4j.model;

/**
 * 治理事件 — 治理动作发生时写入的时间线唯一权威来源行（只追加，不修改）。
 *
 * <p>reject 与 rollback 不在画像/归档行上留下状态痕迹（无状态痕迹的动作没有派生
 * 重建路径，只能发生时写入），事件是它们唯一的审计载体；其余动词的事件是状态变迁的
 * 并行时间线。audit 命令按 actor 的 agent:* 前缀过滤本表（agent 透镜），全量时间线
 * 是事件表本体。</p>
 *
 * @author axy-yxa
 * @since 2026-09-14
 */
public class GovernanceEvent {

    /**
     * 发生时刻（毫秒 epoch）——由存储实现方在写入时刻写入审批记录，调用方不携带
     */
    private Long happenedAt;
    /**
     * 操作主体（agent 以 agent: 前缀申报；框架并入时操作主体为 null）
     */
    private String actor;
    private GovernanceVerb verb;
    private String invocationKey;
    /**
     * 按动词取义：accept=新版本 / reject=保留版本 / rollback=目标版本 /
     * establish、force=建档版本 / collect=当前版本
     */
    private String versionTag;
    /**
     * 代码锚（申报制，随动作留痕；可空）
     */
    private String codeRef;
    /**
     * 补充说明（预留字段，当前动词均不携带）
     */
    private String note;

    public Long getHappenedAt() {
        return happenedAt;
    }

    public void setHappenedAt(Long happenedAt) {
        this.happenedAt = happenedAt;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public GovernanceVerb getVerb() {
        return verb;
    }

    public void setVerb(GovernanceVerb verb) {
        this.verb = verb;
    }

    public String getInvocationKey() {
        return invocationKey;
    }

    public void setInvocationKey(String invocationKey) {
        this.invocationKey = invocationKey;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public void setVersionTag(String versionTag) {
        this.versionTag = versionTag;
    }

    public String getCodeRef() {
        return codeRef;
    }

    public void setCodeRef(String codeRef) {
        this.codeRef = codeRef;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
