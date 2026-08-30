package io.github.agentassert4j.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 验收基线包 — 开发侧基线的可交付载体（`agentassert4j.acceptance-pack/1`）。
 *
 * <p>包内容天然脱敏：只携带结构指纹与调用点键，不携带用户输入输出、rules 配置
 * 与模板原文；{@code --include-samples} 附加的样本已在导出侧强制脱敏（见
 * {@link BaselineStep}）。序列化/反序列化经 PackCodec，schema 字段即版本守卫。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class AcceptancePack {

    public static final String SCHEMA = "agentassert4j.acceptance-pack/1";

    private PackMeta meta;
    private final List<PackTask> tasks = new ArrayList<>();

    public PackMeta getMeta() {
        return meta;
    }

    public void setMeta(PackMeta meta) {
        this.meta = meta;
    }

    public List<PackTask> getTasks() {
        return tasks;
    }

    /**
     * 包元信息 — 导出环境与判定口径的版本守卫依据
     */
    public static class PackMeta {

        private long exportedAt;
        private String exportedBy;
        private String judgmentSemantics;
        private int storageSchemaVersion;
        private String frameworkVersion;
        /**
         * 导出侧 servedModel 去重并集（跨模型验收时的对照参照）
         */
        private String servedModel;

        public long getExportedAt() {
            return exportedAt;
        }

        public void setExportedAt(long exportedAt) {
            this.exportedAt = exportedAt;
        }

        public String getExportedBy() {
            return exportedBy;
        }

        public void setExportedBy(String exportedBy) {
            this.exportedBy = exportedBy;
        }

        public String getJudgmentSemantics() {
            return judgmentSemantics;
        }

        public void setJudgmentSemantics(String judgmentSemantics) {
            this.judgmentSemantics = judgmentSemantics;
        }

        public int getStorageSchemaVersion() {
            return storageSchemaVersion;
        }

        public void setStorageSchemaVersion(int storageSchemaVersion) {
            this.storageSchemaVersion = storageSchemaVersion;
        }

        public String getFrameworkVersion() {
            return frameworkVersion;
        }

        public void setFrameworkVersion(String frameworkVersion) {
            this.frameworkVersion = frameworkVersion;
        }

        public String getServedModel() {
            return servedModel;
        }

        public void setServedModel(String servedModel) {
            this.servedModel = servedModel;
        }
    }

    /**
     * 包内任务 — 一条任务链的有序基线步骤
     */
    public static class PackTask {

        private String taskKey;
        private String requestText;
        private boolean declared;
        private long baselineTime;
        private final List<BaselineStep> steps = new ArrayList<>();

        public String getTaskKey() {
            return taskKey;
        }

        public void setTaskKey(String taskKey) {
            this.taskKey = taskKey;
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

        public long getBaselineTime() {
            return baselineTime;
        }

        public void setBaselineTime(long baselineTime) {
            this.baselineTime = baselineTime;
        }

        public List<BaselineStep> getSteps() {
            return steps;
        }
    }
}
