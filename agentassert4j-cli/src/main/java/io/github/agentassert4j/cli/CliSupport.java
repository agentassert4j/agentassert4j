package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.algorithm.ParameterValueTracer;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.GraphStore;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;

import java.util.Set;
import java.util.TreeSet;

/**
 * CLI 支撑工具 — 存储打开、skill 枚举、依赖图加载的共用逻辑。
 *
 * <p>skill 枚举走「session 全量 → 记录提取」通道，不扩张查询域接口；
 * 图快照缺失或损坏时退化为空图（图是可从交互记录重建的派生数据）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
final class CliSupport {

    private CliSupport() {
    }

    /**
     * 打开默认 SQLite 存储并初始化（建表/版本检查）。
     *
     * @param dbOverride 显式数据库路径（--db），null 时取 agentassert4j.json 的 storage.url
     * @return 已初始化的存储仓库（调用方负责 close）
     */
    static SqliteStorageRepository openRepository(String dbOverride) {
        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        String url = dbOverride != null ? dbOverride : config.getStorage().getUrl();
        SqliteStorageRepository repository = new SqliteStorageRepository(expandHome(url));
        repository.initialize();
        return repository;
    }

    /**
     * 展开 "~" 前缀为用户主目录（配置默认值使用 ~/.agentassert4j/ 约定）。
     */
    static String expandHome(String path) {
        if (path == null || !path.startsWith("~")) {
            return path;
        }
        String home = System.getProperty("user.home", "");
        if (path.length() == 1) {
            return home;
        }
        if (path.charAt(1) == '/' || path.charAt(1) == '\\') {
            return home + path.substring(1);
        }
        return path;
    }

    /**
     * 枚举已录制的 skillId（按字典序稳定）。
     */
    static Set<String> recordedSkillIds(InteractionQueryStore repository) {
        Set<String> skillIds = new TreeSet<>();
        for (String sessionId : repository.findAllSessionIds()) {
            for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                if (record.getSkillId() != null && !record.getSkillId().isEmpty()) {
                    skillIds.add(record.getSkillId());
                }
            }
        }
        return skillIds;
    }

    /**
     * 加载依赖图快照；无快照或解析失败返回空图。
     */
    static InMemoryDependencyGraph loadGraphOrDefault(GraphStore repository) {
        try {
            String json = repository.loadGraph();
            if (json != null && !json.isEmpty()) {
                return InMemoryDependencyGraph.fromJson(json);
            }
        } catch (RuntimeException e) {
            // 图是派生数据：坏快照不阻断影响分析，退化为空图（仅直接受影响 Skill）
        }
        return new InMemoryDependencyGraph();
    }

    /**
     * 从交互记录现场重建依赖图（只读，不落盘）。
     * 图是派生数据，重建永远反映最新录制状态；全量扫描在 v1 规模（数千条）
     * 毫秒级，轻量列裁剪与增量构建按既定决策延迟。
     */
    static InMemoryDependencyGraph rebuildGraph(StorageRepository repository) {
        ParameterValueTracer tracer = new ParameterValueTracer();
        tracer.rebuildGraph(repository);
        return tracer.getGraph();
    }

    /**
     * 当前操作者身份（审批留痕的缺省来源）：user.name，缺失时显式记为 unknown
     * 而非留下无主审批记录。
     */
    static String currentActor() {
        String user = System.getProperty("user.name");
        return user != null && !user.trim().isEmpty() ? user.trim() : "unknown";
    }
}
