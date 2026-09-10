package io.github.agentassert4j.algorithm;

/**
 * 治理写的乐观并发守卫：写入前提（活跃版本标签）与调用方所见不一致时抛出——
 * 多宿主共享同一库时，判定/巡检与裁决两步之间活跃版本可能被并行改写，
 * 就近拒绝优于静默覆盖。命令层将其翻译为 E-GUARD 包络。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public class VersionMismatchException extends IllegalStateException {

    public VersionMismatchException(String message) {
        super(message);
    }
}
