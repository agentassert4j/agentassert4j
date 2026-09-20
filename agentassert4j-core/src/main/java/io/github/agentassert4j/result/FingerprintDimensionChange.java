package io.github.agentassert4j.result;

import java.util.ArrayList;
import java.util.List;

/**
 * 单维度指纹差异 — 基线与候选在该维度上的两侧取值与差集。
 *
 * <p>{@code baselineView}/{@code candidateView} 是维度的紧凑展示串
 * （集合按字典序渲染，标量原样，null 标量渲染为 {@code "null"}）；
 * {@code added}/{@code removed}/{@code changed} 携带逐项差集供机器消费；
 * {@code detail} 是映射类维度的人读行内段（与两侧视图同一次比较产出，
 * 不存在第二份手写格式）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public class FingerprintDimensionChange {

    private FingerprintDiffDimension dimension;
    private String baselineView;
    private String candidateView;
    private List<String> added = new ArrayList<>();
    private List<String> removed = new ArrayList<>();
    private List<String> changed = new ArrayList<>();
    private String detail;

    public FingerprintDiffDimension getDimension() {
        return dimension;
    }

    public void setDimension(FingerprintDiffDimension dimension) {
        this.dimension = dimension;
    }

    public String getBaselineView() {
        return baselineView;
    }

    public void setBaselineView(String baselineView) {
        this.baselineView = baselineView;
    }

    public String getCandidateView() {
        return candidateView;
    }

    public void setCandidateView(String candidateView) {
        this.candidateView = candidateView;
    }

    public List<String> getAdded() {
        return added;
    }

    public void setAdded(List<String> added) {
        this.added = added;
    }

    public List<String> getRemoved() {
        return removed;
    }

    public void setRemoved(List<String> removed) {
        this.removed = removed;
    }

    public List<String> getChanged() {
        return changed;
    }

    public void setChanged(List<String> changed) {
        this.changed = changed;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
