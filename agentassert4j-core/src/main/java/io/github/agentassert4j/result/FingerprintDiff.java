package io.github.agentassert4j.result;

import java.util.ArrayList;
import java.util.List;

/**
 * 指纹差异 — 基线与候选指纹逐维对照的结构化结果。
 *
 * <p>差异语义的唯一权威载体：人读渲染（CLI 巡检视图）与机器报告
 * （candidate-diff/1）都从本模型投影，两侧不得各自实现比较规则。
 * 只收录存在差异的维度；两侧任一为 null 按空指纹处理（无对照时
 * 全部差异表现为「新增」侧）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public class FingerprintDiff {

    private List<FingerprintDimensionChange> changes = new ArrayList<>();

    public List<FingerprintDimensionChange> getChanges() {
        return changes;
    }

    public void setChanges(List<FingerprintDimensionChange> changes) {
        this.changes = changes;
    }

    /**
     * 两指纹在全部维度上一致（无任何差异条目）。
     */
    public boolean isIdentical() {
        return changes.isEmpty();
    }
}
