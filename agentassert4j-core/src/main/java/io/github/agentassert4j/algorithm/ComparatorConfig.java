package io.github.agentassert4j.algorithm;

import java.util.Collections;
import java.util.Set;

/**
 * 对比器配置 — 可忽略字段、阈值等。
 *
 * <p>当前阶段提供默认空配置。待 config 包实现后从 agentassert4j.yml 加载。</p>
 */
public class ComparatorConfig {

    private Set<String> ignorableFields = Collections.emptySet();

    public Set<String> getIgnorableFields() {
        return ignorableFields;
    }

    public void setIgnorableFields(Set<String> ignorableFields) {
        this.ignorableFields = ignorableFields != null ? ignorableFields : Collections.emptySet();
    }

    /**
     * 创建默认配置（无可忽略字段）。
     */
    public static ComparatorConfig defaults() {
        return new ComparatorConfig();
    }
}
