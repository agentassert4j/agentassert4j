package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.List;
import java.util.Set;

/**
 * 交互记录查询域 SPI — 分析管道（分组/指纹/图谱/影响分析）的读取面。
 *
 * <p>实现方必须保证返回顺序确定性（按 timestamp、seq、record_id 稳定排序），
 * 同一数据重复查询返回相同顺序——依赖边构建的可复现性依赖这一点。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface InteractionQueryStore {

    /**
     * 按声明标签查询该标签的全部交互记录
     */
    List<InteractionRecord> findByInvocationId(String invocationId);

    /**
     * 按调用点键查询该调用点的全部交互记录（影响分析选例与治理目标解析用）
     */
    List<InteractionRecord> findByInvocationKey(String invocationKey);

    /**
     * 按 session 查询（依赖链重建用），返回按确定性排序键有序
     */
    List<InteractionRecord> findBySessionId(String sessionId);

    /**
     * 按 record_id 精确查询单条交互（排障/取证面：回显原始 wire 双列）。
     * record_id 全局幂等，命中至多一条；未命中返回 null。
     */
    InteractionRecord findByRecordId(String recordId);

    /**
     * 获取所有 session ID（任务链派生与分桶枚举使用）
     */
    List<String> findAllSessionIds();
}
