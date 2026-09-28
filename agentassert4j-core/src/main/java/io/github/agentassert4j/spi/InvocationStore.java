package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.InvocationProfile;

import java.util.List;

/**
 * 调用点域 SPI — 基线三态生命周期（BASELINE/CANDIDATE/ARCHIVED）的载体存储。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface InvocationStore {

    /**
     * 保存/更新调用点画像
     */
    void saveInvocationProfile(InvocationProfile p);

    /**
     * 占位式保存：键未被占用时写入并返回 true；已被占用时不改动现况返回 false。
     * 并发建档的先后由单条 INSERT 的原子性裁决——后到者据此如实降级为
     * 「已存在」语义，而不是覆盖先到者的画像。
     */
    boolean saveInvocationProfileIfAbsent(InvocationProfile p);

    /**
     * 按调用点键查询画像
     */
    InvocationProfile findInvocationByKey(String invocationKey);

    /**
     * 全量画像（冷启动检测与裁决目标解析用）
     */
    List<InvocationProfile> findAllInvocations();
}
