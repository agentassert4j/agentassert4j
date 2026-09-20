package io.github.agentassert4j.util;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.Map;

/**
 * 重驱观测标记 — metadata 携带的观测身份判据的唯一权威实现。
 *
 * <p>受控重驱的真调结果作为观测记录落库（同键新执行），携带
 * {@code redriveOf}（被重驱记录的 recordId）与 {@code redriveTemplateHash}
 * （所用归档模板哈希）两个 metadata 键。任务链派生与漂移检测的「最新身份」
 * 消费本判据排除观测记录——检测仪器产生的执行不是业务执行，混入会污染
 * 后续判定。判定只认结构化解析：metadata 非对象或解析失败按非观测处理
 * （安全退化，不中断流程），禁止对 metadata 做子串包含式猜测。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public final class RedriveMarkerUtil {

    /**
     * 被重驱记录的 recordId 所在 metadata 键
     */
    public static final String REDRIVE_OF = "redriveOf";

    /**
     * 重驱所用归档模板哈希所在 metadata 键
     */
    public static final String REDRIVE_TEMPLATE_HASH = "redriveTemplateHash";

    private RedriveMarkerUtil() {
    }

    /**
     * 记录是否为重驱观测：metadata 为 JSON 对象且携带非空白 {@code redriveOf} 值。
     */
    public static boolean isRedriveObservation(InteractionRecord record) {
        if (record == null) {
            return false;
        }
        String metadata = record.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        try {
            Object parsed = RecursiveJsonParser.parse(metadata);
            if (parsed instanceof Map) {
                Object value = ((Map<?, ?>) parsed).get(REDRIVE_OF);
                return value != null && !String.valueOf(value).trim().isEmpty();
            }
        } catch (RuntimeException ignored) {
            // 损坏 metadata 按非观测处理
        }
        return false;
    }
}
