package io.github.agentassert4j.cli;

/**
 * 机器失败包络（agentassert4j.error/1）的错误码封闭词表。
 * wireName 是冻结的 JSON 线上值；新增族 = 公开契约变更，须同步 spec 与 wire 测试钉。
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
enum CliErrorCode {

    /**
     * 用法/参数问题：旗标组合违规、预算参数越界、选择器值多命中歧义、重驱预算截断。
     */
    E_USAGE("E-USAGE"),

    /**
     * 无可操作对象：空库/缩域为空、目标不存在、无候选、无归档版本、验收覆盖缺口。
     */
    E_NO_DATA("E-NO-DATA"),

    /**
     * 判定守卫拒绝：--ci 未建档拒绝判定、判定语义版本失配（本地画像/验收包）。
     */
    E_GUARD("E-GUARD"),

    /**
     * 环境/存储/IO/LLM：库打不开、包文件不可读、写盘失败、全部重驱调用失败。
     */
    E_ENV("E-ENV");

    private final String wireName;

    CliErrorCode(String wireName) {
        this.wireName = wireName;
    }

    /**
     * JSON 线上值（冻结契约，含连字符，与枚举常量名的下划线形态不同）。
     */
    String wireName() {
        return wireName;
    }
}
