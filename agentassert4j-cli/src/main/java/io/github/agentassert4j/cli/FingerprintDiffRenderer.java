package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintDiffer;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.result.FingerprintDiff;
import io.github.agentassert4j.result.FingerprintDimensionChange;

import java.util.ArrayList;
import java.util.List;

/**
 * 指纹差异渲染 — 把结构化差异渲染成人类可读行。
 *
 * <p>裁决（accept/reject）的依据是候选与基线的差异内容，而 replay 的 summary
 * 是易失的进程输出，裁决常发生在另一进程另一时刻——本渲染器把持久化在画像里的
 * 两份指纹并列呈现给裁决者，补齐裁决时看不到基线证据的缺口。
 * 差异语义的计算唯一来源是 {@link FingerprintDiffer}（结构化模型），本类只做
 * 人读投影；candidate-diff/1 的机器报告消费同一模型——两侧格式不会各自漂移。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
final class FingerprintDiffRenderer {

    private FingerprintDiffRenderer() {
    }

    /**
     * 渲染基线 → 候选的差异行（每行一条，无差异时恰一行「指纹一致」）。
     */
    static List<String> render(DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        return render(FingerprintDiffer.diff(baseline, candidate));
    }

    /**
     * 渲染结构化差异（行格式与差异计算解耦后的唯一渲染入口）。
     */
    static List<String> render(FingerprintDiff diff) {
        List<String> lines = new ArrayList<>();
        for (FingerprintDimensionChange change : diff.getChanges()) {
            lines.add(renderChange(change));
        }
        if (lines.isEmpty()) {
            lines.add("Candidate fingerprint matches the baseline in all dimensions.");
        }
        return lines;
    }

    private static String renderChange(FingerprintDimensionChange change) {
        switch (change.getDimension()) {
            case TOOL_SET:
                StringBuilder sb = new StringBuilder("Tool set: ").append(change.getBaselineView()).append(" → ").append(change.getCandidateView());
                if (!change.getAdded().isEmpty()) {
                    sb.append(" (added ").append(bracketed(change.getAdded())).append(")");
                }
                if (!change.getRemoved().isEmpty()) {
                    sb.append(" (removed ").append(bracketed(change.getRemoved())).append(")");
                }
                return sb.toString();
            case OUTPUT_FIELDS:
                StringBuilder fields = new StringBuilder("Output field set:");
                if (!change.getAdded().isEmpty()) {
                    fields.append(" added ").append(bracketed(change.getAdded())).append(";");
                }
                if (!change.getRemoved().isEmpty()) {
                    fields.append(" removed ").append(bracketed(change.getRemoved())).append(";");
                }
                return fields.toString();
            case PARAM_TYPES:
                return "Param types:" + change.getDetail();
            case FIELD_TYPES:
                return "Field types:" + change.getDetail();
            case OUTPUT_CONTENT_TYPE:
                return "Output content type: " + change.getBaselineView() + " → " + change.getCandidateView();
            case OUTPUT_LENGTH_MAGNITUDE:
                return "Output length magnitude: " + change.getBaselineView() + " → " + change.getCandidateView();
            case REQUIRED_KEYWORDS:
                return "Required keywords: " + change.getBaselineView() + " → " + change.getCandidateView();
            case FORBIDDEN_KEYWORDS:
                return "Forbidden keywords: " + change.getBaselineView() + " → " + change.getCandidateView();
            case REGEX_RULE_COUNT:
                return "Regex rule count: " + change.getBaselineView() + " → " + change.getCandidateView();
            case DECLARED_BEHAVIORS:
                return "Declared behaviors: " + change.getBaselineView() + " → " + change.getCandidateView();
            case ERROR_MARKER:
                return "Error marker: " + change.getBaselineView() + " → " + change.getCandidateView();
            default:
                // 封闭词表扩展新维度时这里必须失败——静默跳过会让新维度在人读面消失
                throw new IllegalStateException("Unhandled fingerprint diff dimension: " + change.getDimension());
        }
    }

    private static StringBuilder bracketed(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.append(']');
    }
}
