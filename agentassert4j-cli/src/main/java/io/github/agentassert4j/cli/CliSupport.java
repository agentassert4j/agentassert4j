package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BehaviorChecker;
import io.github.agentassert4j.algorithm.DeterministicSkillGrouper;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.algorithm.ParameterValueTracer;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.GraphStore;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;

import java.io.PrintStream;
import java.util.*;

/**
 * CLI 支撑工具 — 存储打开、skill 枚举、依赖图加载、--skill 目标解析的共用逻辑。
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
        return openRepository(dbOverride, System.out);
    }

    static SqliteStorageRepository openRepository(String dbOverride, PrintStream out) {
        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        // 隐式查找链（cwd → home → classpath）命中了哪个文件必须就地披露——
        // 错误目录下运行时旧配置静默生效是最难查的排障黑洞
        String configSource = ConfigLoader.describeMainConfigSource();
        out.println(configSource != null ? "配置：" + configSource : "配置：未找到 agentassert4j.json，使用内置默认值。");
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

    /**
     * 解析 --skill 过滤值（选例类命令用：replay/baseline）。与某业务 skillId
     * 精确相等时按原义使用；否则尝试 groupKey 唯一前缀匹配并换算回业务标签
     * （画像上的 skillId 是分组器派生的内部标识，与记录上的业务标签是两套体系）。
     * 完全无命中时原样返回，由调用方的「未找到用例」路径兜底。
     *
     * @return 业务 skillId 过滤值（null 语义由调用方维持）
     */
    static String resolveBusinessSkillFilter(StorageRepository repository, String filter, PrintStream out) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        Set<String> businessIds = recordedSkillIds(repository);
        if (businessIds.contains(filter)) {
            return filter;
        }
        List<SkillProfile> prefixMatches = new ArrayList<>();
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getGroupKey() != null && profile.getGroupKey().startsWith(filter)) {
                prefixMatches.add(profile);
            }
        }
        if (prefixMatches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (SkillProfile profile : prefixMatches) {
                keys.add(profile.getGroupKey());
            }
            throw new IllegalStateException("--skill " + filter + " 前缀匹配到多个 skill：" + String.join(", ", keys) + "，请提供更长前缀。");
        }
        if (prefixMatches.isEmpty()) {
            return filter;
        }
        String targetGroupKey = prefixMatches.get(0).getGroupKey();
        List<String> businessMatches = new ArrayList<>();
        for (String skillId : businessIds) {
            List<InteractionRecord> records = repository.findBySkillId(skillId);
            if (!records.isEmpty() && targetGroupKey.equals(groupKeyOfRecord(records.get(0)))) {
                businessMatches.add(skillId);
            }
        }
        if (businessMatches.size() == 1) {
            out.println("提示：--skill " + filter + " 按 groupKey 前缀匹配到 " + targetGroupKey + "（业务标签 " + businessMatches.get(0) + "）");
            return businessMatches.get(0);
        }
        if (businessMatches.size() > 1) {
            throw new IllegalStateException("--skill " + filter + " 对应分组覆盖多个业务标签：" + String.join(", ", businessMatches) + "，请使用确切的业务标签。");
        }
        return filter;
    }

    /**
     * 解析 --skill 目标值（画像操作类命令用：approve/reject/rollback），返回唯一 groupKey。
     * 解析优先级：完整 groupKey 精确命中（即使它是其他 key 的前缀）＞ 业务标签（该标签
     * 覆盖多个分组时报错并列出）＞ groupKey 唯一前缀。无命中或多命中均抛
     * {@link IllegalStateException}，由命令层转译为退出码 2。
     */
    static String resolveGroupKeyTarget(StorageRepository repository, String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalStateException("缺少目标 skill。");
        }
        for (SkillProfile profile : repository.findAllSkills()) {
            if (filter.equals(profile.getGroupKey())) {
                return filter;
            }
        }
        Set<String> businessIds = recordedSkillIds(repository);
        if (businessIds.contains(filter)) {
            Set<String> groupKeys = new LinkedHashSet<>();
            for (InteractionRecord record : repository.findBySkillId(filter)) {
                String groupKey = groupKeyOfRecord(record);
                if (groupKey != null) {
                    groupKeys.add(groupKey);
                }
            }
            if (groupKeys.isEmpty()) {
                throw new IllegalStateException("业务标签 " + filter + " 下没有可分组的录制记录，无法定位 skill。");
            }
            if (groupKeys.size() > 1) {
                throw new IllegalStateException("业务标签 " + filter + " 覆盖多个分组：" + String.join(", ", groupKeys) + "，请用 groupKey（或其唯一前缀）指定。");
            }
            return groupKeys.iterator().next();
        }
        List<SkillProfile> prefixMatches = new ArrayList<>();
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getGroupKey() != null && profile.getGroupKey().startsWith(filter)) {
                prefixMatches.add(profile);
            }
        }
        if (prefixMatches.isEmpty()) {
            throw new IllegalStateException("没有匹配 " + filter + " 的 skill（业务标签或 groupKey 前缀，完整列表见 status 命令）。");
        }
        if (prefixMatches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (SkillProfile profile : prefixMatches) {
                keys.add(profile.getGroupKey());
            }
            throw new IllegalStateException("前缀匹配到多个 skill：" + String.join(", ", keys) + "，请提供更长的前缀。");
        }
        return prefixMatches.get(0).getGroupKey();
    }

    /**
     * 单条记录的分组键；分组失败的记录返回 null（调用方决定跳过或报错）。
     */
    private static String groupKeyOfRecord(InteractionRecord record) {
        try {
            return DeterministicSkillGrouper.group(record).getGroupKey();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 规则声明里出现未知 behavior 名时告警——未知名在判定中被静默视为通过，
     * 笔误（如 noErr 写成 noErr0）会让维度 4 满分化、CI 照绿，必须在加载时点破。
     */
    static void warnUnknownBehaviors(SkillRulesConfig rules, PrintStream out) {
        Set<String> builtins = BehaviorChecker.getBuiltinBehaviorNames();
        for (String skillId : rules.getDeclaredSkillIds()) {
            for (String behavior : rules.getRulesForSkill(skillId).getBehaviors()) {
                if (!builtins.contains(behavior)) {
                    out.println("警告：skill " + skillId + " 声明了未知行为 " + behavior + "（该规则将被忽略）。合法行为名：" + String.join(", ", new TreeSet<>(builtins)));
                }
            }
        }
    }
}
