package io.github.agentassert4j.spi;

import java.util.List;

/**
 * 存储健康检查域 — 全库一致性深扫（doctor 体检面专用）。
 *
 * <p>与查询域的区别：查询按需触页，损坏页落在未触区域时静默不可见；
 * 本域的检查全量扫页，把「未触页的物理损坏」从静默绿灯变成就近可见的
 * 异常清单。实现失败按存储层退化语义处理（空清单 + SEVERE 日志），不中断流程。</p>
 *
 * @author axy-yxa
 * @since 2026-10-02
 */
public interface StorageHealthStore {

    /**
     * 全库一致性检查（SQLite 方言即 PRAGMA quick_check）。
     *
     * @return 异常描述清单（逐条人读）；空清单 = 库完好
     */
    List<String> quickCheckFindings();
}
