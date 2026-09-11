package io.github.agentassert4j.cli;

/**
 * 报告面 schema 名单源 — 各单行 JSON 报告 "schema" 字段的取值。写出方与读回方
 * （如 MCP structuredContent 的错误包络抽取）一律引用本清单，禁止裸写字面量；
 * 新增报告面在此登记。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
final class ReportSchemas {

    static final String ERROR = "agentassert4j.error/1";
    static final String RECORD = "agentassert4j.record/1";
    static final String RECORD_VIEW = "agentassert4j.record-view/1";
    static final String STATUS = "agentassert4j.status/1";
    static final String BASELINE_REPORT = "agentassert4j.baseline-report/1";
    static final String ADJUDICATION = "agentassert4j.adjudication/1";
    static final String ROLLBACK = "agentassert4j.rollback/1";
    static final String AUDIT = "agentassert4j.audit/1";
    static final String DOCTOR = "agentassert4j.doctor/1";
    static final String GRAPH = "agentassert4j.graph/1";
    static final String RULES = "agentassert4j.rules/1";
    static final String EXPORT_REPORT = "agentassert4j.export-report/1";
    static final String VERIFY_REPORT = "agentassert4j.verify-report/1";
    static final String TASK_REPORT = "agentassert4j.task-report/1";

    private ReportSchemas() {
    }
}
