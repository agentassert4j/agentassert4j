package io.github.agentassert4j.result;

/**
 * 任务规则违规 — 任务链违反 rules.tasks 声明纪律的单条判定明细。
 *
 * <p>违规折叠进既有二值判定（链级 CHANGED），不引入新的 verdict 值；
 * 呈现顺序钉死为规则声明序：requiredSteps → requiredOrder → steps。</p>
 *
 * @author axy-yxa
 * @since 2026-09-01
 */
public class TaskRuleViolation {

    /**
     * 违规类型
     */
    public enum Type {
        /**
         * 声明的必备步骤标签在链内一次也未出现
         */
        REQUIRED_STEP_MISSING,
        /**
         * 声明标签的出现次数超出 min/max 绝对范围
         */
        STEP_COUNT_OUT_OF_RANGE,
        /**
         * 声明的标签序列未按相对顺序出现（含任一标签未出现）
         */
        ORDER_VIOLATION
    }

    private final Type type;
    private final String label;
    private final String detail;

    public TaskRuleViolation(Type type, String label, String detail) {
        this.type = type;
        this.label = label;
        this.detail = detail;
    }

    public Type getType() {
        return type;
    }

    /**
     * 违规涉及的步骤标签；顺序违规时为声明序列的逗号连接
     */
    public String getLabel() {
        return label;
    }

    /**
     * 人读明细（面向报告与 JSON 的 detail 字段）
     */
    public String getDetail() {
        return detail;
    }
}
