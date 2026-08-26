package io.github.agentassert4j.spi;

/**
 * 依赖图谱快照域 SPI — 整体 JSON 快照存取，非逐行存边。
 *
 * <p>图是派生数据（双向门）：可随时由交互记录重建，存储层只做快照持久化。</p>
 */
public interface GraphStore {

    void saveGraph(String graphJson);

    String loadGraph();
}
