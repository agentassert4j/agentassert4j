package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.result.FingerprintDiff;
import io.github.agentassert4j.result.FingerprintDiffDimension;
import io.github.agentassert4j.result.FingerprintDimensionChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FingerprintDiffer 的单元测试 — 逐维差异语义：集合按字典序、映射按键序、
 * 标量按字符串等值、单侧 null 的维度不参与比较。结构化差异是人读渲染与
 * candidate-diff/1 机器报告的共同真源，判定规则一经发布冻结。
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
class FingerprintDifferTest {

    private DeterministicFingerprint fingerprint() {
        return new DeterministicFingerprint();
    }

    @Test
    @DisplayName("两侧一致：零差异条目，identical=true")
    void identicalFingerprints_produceNoChanges() {
        DeterministicFingerprint base = fingerprint();
        base.setToolCallSet(set("search"));
        DeterministicFingerprint cand = fingerprint();
        cand.setToolCallSet(set("search"));

        FingerprintDiff diff = FingerprintDiffer.diff(base, cand);

        assertTrue(diff.isIdentical(), "全部维度一致时不产生条目: " + diff.getChanges());
    }

    @Test
    @DisplayName("工具集合差异：两侧视图与差集，字典序稳定")
    void toolSetDiff_carriesViewsAndSets() {
        DeterministicFingerprint base = fingerprint();
        base.setToolCallSet(set("search", "log"));
        DeterministicFingerprint cand = fingerprint();
        cand.setToolCallSet(set("search", "fetch"));

        FingerprintDiff diff = FingerprintDiffer.diff(base, cand);

        assertEquals(1, diff.getChanges().size());
        FingerprintDimensionChange change = diff.getChanges().get(0);
        assertEquals(FingerprintDiffDimension.TOOL_SET, change.getDimension());
        assertEquals("[log, search]", change.getBaselineView());
        assertEquals("[fetch, search]", change.getCandidateView());
        assertEquals(Arrays.asList("fetch"), change.getAdded());
        assertEquals(Arrays.asList("log"), change.getRemoved());
    }

    @Test
    @DisplayName("映射差异：removed/changed/added 逐项，detail 是有序行内段")
    void mapDiff_itemizesRemovedChangedAdded() {
        DeterministicFingerprint base = fingerprint();
        Map<String, String> baseParams = new HashMap<>();
        baseParams.put("city", "string");
        baseParams.put("order", "number");
        base.setToolParamTypes(baseParams);
        DeterministicFingerprint cand = fingerprint();
        Map<String, String> candParams = new HashMap<>();
        candParams.put("order", "text");
        candParams.put("user", "string");
        cand.setToolParamTypes(candParams);

        FingerprintDiff diff = FingerprintDiffer.diff(base, cand);

        FingerprintDimensionChange change = find(diff, FingerprintDiffDimension.PARAM_TYPES);
        assertEquals(Arrays.asList("user:string"), change.getAdded());
        assertEquals(Arrays.asList("city:string"), change.getRemoved());
        assertEquals(Arrays.asList("order(number→text)"), change.getChanged());
        assertEquals(" removed city:string; order(number→text); added user:string;", change.getDetail());
    }

    @Test
    @DisplayName("标量差异：null 渲染为字符串 null 参与等值比较")
    void scalarDiff_rendersNullAsLiteral() {
        DeterministicFingerprint base = fingerprint();
        base.setOutputContentType(null);
        DeterministicFingerprint cand = fingerprint();
        cand.setOutputContentType("text");

        FingerprintDiff diff = FingerprintDiffer.diff(base, cand);

        FingerprintDimensionChange change = find(diff, FingerprintDiffDimension.OUTPUT_CONTENT_TYPE);
        assertEquals("null", change.getBaselineView());
        assertEquals("text", change.getCandidateView());
    }

    @Test
    @DisplayName("指纹缺席一侧：长度量级与错误标记不产生差异条目（无对照即无差异证据）")
    void absentFingerprintSide_skipsPresenceOnlyDimensions() {
        DeterministicFingerprint cand = fingerprint();
        cand.setTextLengthMagnitude(3);
        cand.setHasError(true);

        FingerprintDiff diff = FingerprintDiffer.diff(null, cand);

        assertFalse(has(diff, FingerprintDiffDimension.OUTPUT_LENGTH_MAGNITUDE), "基线指纹缺席不参与长度量级比较");
        assertFalse(has(diff, FingerprintDiffDimension.ERROR_MARKER), "基线指纹缺席不参与错误标记比较");
    }

    @Test
    @DisplayName("两侧在场时错误标记与长度量级差异如实成条")
    void presenceOnlyDimensions_diffWhenBothPresent() {
        DeterministicFingerprint base = fingerprint();
        base.setHasError(false);
        base.setTextLengthMagnitude(2);
        DeterministicFingerprint cand = fingerprint();
        cand.setHasError(true);
        cand.setTextLengthMagnitude(4);

        FingerprintDiff diff = FingerprintDiffer.diff(base, cand);

        assertEquals("no", find(diff, FingerprintDiffDimension.ERROR_MARKER).getBaselineView());
        assertEquals("yes", find(diff, FingerprintDiffDimension.ERROR_MARKER).getCandidateView());
        assertEquals("2", find(diff, FingerprintDiffDimension.OUTPUT_LENGTH_MAGNITUDE).getBaselineView());
        assertEquals("4", find(diff, FingerprintDiffDimension.OUTPUT_LENGTH_MAGNITUDE).getCandidateView());
    }

    @Test
    @DisplayName("无基线对照（null）：全部候选侧差异表现为新增")
    void nullBaseline_treatedAsEmpty() {
        DeterministicFingerprint cand = fingerprint();
        cand.setToolCallSet(set("search"));

        FingerprintDiff diff = FingerprintDiffer.diff(null, cand);

        FingerprintDimensionChange change = find(diff, FingerprintDiffDimension.TOOL_SET);
        assertEquals("[]", change.getBaselineView());
        assertEquals(Arrays.asList("search"), change.getAdded());
    }

    private static FingerprintDimensionChange find(FingerprintDiff diff, FingerprintDiffDimension dimension) {
        for (FingerprintDimensionChange change : diff.getChanges()) {
            if (change.getDimension() == dimension) {
                return change;
            }
        }
        fail("缺少维度条目 " + dimension);
        return null;
    }

    private static boolean has(FingerprintDiff diff, FingerprintDiffDimension dimension) {
        for (FingerprintDimensionChange change : diff.getChanges()) {
            if (change.getDimension() == dimension) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
