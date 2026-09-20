package io.github.agentassert4j.util;

import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedriveMarkerUtil 的单元测试 — 观测判据只认结构化解析：
 * 携带非空白 redriveOf 的对象型 metadata 才是观测记录，
 * 损坏/缺失/非对象一律按业务记录处理（安全退化）。
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
class RedriveMarkerUtilTest {

    private InteractionRecord record(String metadata) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("r1");
        r.setMetadata(metadata);
        return r;
    }

    @Test
    @DisplayName("携带非空白 redriveOf 的对象型 metadata 判为观测")
    void objectMetadataWithMarker_isObservation() {
        assertTrue(RedriveMarkerUtil.isRedriveObservation(record("{\"redriveOf\":\"rec-1\",\"redriveTemplateHash\":\"ab12\"}")));
        assertTrue(RedriveMarkerUtil.isRedriveObservation(record("{\"taskKey\":\"order\",\"redriveOf\":\"rec-1\"}")));
    }

    @Test
    @DisplayName("空白 redriveOf、缺失标记、无 metadata 均按业务记录")
    void blankOrMissingMarker_isBusinessRecord() {
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("{\"redriveOf\":\"  \"}")));
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("{\"taskKey\":\"order\"}")));
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record(null)));
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("")));
    }

    @Test
    @DisplayName("损坏或非对象 metadata 安全退化为业务记录（禁止子串猜测）")
    void brokenMetadata_degradesSafely() {
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("redriveOf=rec-1")));
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("[\"redriveOf\"]")));
        assertFalse(RedriveMarkerUtil.isRedriveObservation(record("not json at all")));
    }

    @Test
    @DisplayName("null 记录与 null metadata 判为业务记录")
    void nullInputs_areBusinessRecords() {
        assertFalse(RedriveMarkerUtil.isRedriveObservation(null));
    }
}
