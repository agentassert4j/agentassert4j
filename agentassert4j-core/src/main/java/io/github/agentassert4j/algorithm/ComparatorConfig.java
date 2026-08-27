package io.github.agentassert4j.algorithm;

import java.util.Collections;
import java.util.Set;

/**
 * 对比器配置 — 可忽略字段、阈值等。
 *
 * <p>默认空配置，阈值与可忽略字段按需设置；外部化加载由调用方组装后注入。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ComparatorConfig {

    private Set<String> ignorableFields = Collections.emptySet();

    /**
     * 创建默认配置（无可忽略字段）。
     */
    public static ComparatorConfig defaults() {
        return new ComparatorConfig();
    }

    public Set<String> getIgnorableFields() {
        return ignorableFields;
    }

    public void setIgnorableFields(Set<String> ignorableFields) {
        this.ignorableFields = ignorableFields != null ? ignorableFields : Collections.emptySet();
    }
}
