package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.InvocationRulesConfig.InvocationRule;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CLI 支撑工具 — 存储打开、调用点 枚举、依赖图加载、--调用点 目标解析的共用逻辑。
 *
 * <p>调用点 枚举走「session 全量 → 记录提取」通道，不扩张查询域接口；
 * 图快照缺失或损坏时退化为空图（图是可从交互记录重建的派生数据）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
final class CliSupport {

    private CliSupport() {
    }

    /**
     * 把 stdout/stderr 换成 UTF-8 直写通道。Windows 控制台默认 GBK 时，
     * CLI 输出的中文与 JSON 符号会以错误编码落盘/显示；这里绕过控制台
     * 编码器，直接以 UTF-8 字节写标准流文件描述符——UTF-8 终端与 CI
     * 日志收集器按 UTF-8 解码即正确。仅 main 入口调用，不影响测试注入
     * 的 PrintStream。
     */
    static void installUtf8Console() {
        System.setOut(utf8PrintStream(new FileOutputStream(FileDescriptor.out)));
        System.setErr(utf8PrintStream(new FileOutputStream(FileDescriptor.err)));
    }

    /**
     * 以 UTF-8 编码包装字节输出流（自动 flush）。
     */
    static PrintStream utf8PrintStream(OutputStream out) {
        try {
            return new PrintStream(out, true, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // JVM 规范强制支持 UTF-8，此分支不可达
            throw new IllegalStateException("UTF-8 charset must be supported", e);
        }
    }

    /**
     * 打开默认 SQLite 存储并初始化（建表/版本检查）。
     *
     * @param dbOverride 显式数据库路径（--db），null 时取 agentassert4j.json 的 storage.url
     * @return 已初始化的存储仓库（调用方负责 close）
     */
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
     * 枚举已录制的 invocationId（按字典序稳定）。
     */
    static Set<String> recordedInvocationIds(InteractionQueryStore repository) {
        Set<String> invocationIds = new TreeSet<>();
        for (String sessionId : repository.findAllSessionIds()) {
            for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                if (record.getInvocationId() != null && !record.getInvocationId().isEmpty()) {
                    invocationIds.add(record.getInvocationId());
                }
            }
        }
        return invocationIds;
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
     * 解析 --调用点 过滤值（选例类命令用：replay/baseline）。与某业务 invocationId
     * 精确相等时按原义使用；否则尝试 invocationKey 唯一前缀匹配并换算回业务标签
     * （画像上的 invocationId 是分组器派生的内部标识，与记录上的业务标签是两套体系）。
     * 完全无命中时原样返回，由调用方的「未找到用例」路径兜底。
     *
     * @return 业务 invocationId 过滤值（null 语义由调用方维持）
     */
    static String resolveInvocationFilter(StorageRepository repository, String filter, PrintStream out) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        Set<String> businessIds = recordedInvocationIds(repository);
        if (businessIds.contains(filter)) {
            return filter;
        }
        List<InvocationProfile> prefixMatches = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getInvocationKey() != null && profile.getInvocationKey().startsWith(filter)) {
                prefixMatches.add(profile);
            }
        }
        if (prefixMatches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (InvocationProfile profile : prefixMatches) {
                keys.add(profile.getInvocationKey());
            }
            throw new IllegalStateException("--invocation " + filter + " 前缀匹配到多个调用点：" + String.join(", ", keys) + "，请提供更长前缀。");
        }
        if (prefixMatches.isEmpty()) {
            return filter;
        }
        String targetInvocationKey = prefixMatches.get(0).getInvocationKey();
        List<String> businessMatches = new ArrayList<>();
        for (String invocationId : businessIds) {
            List<InteractionRecord> records = repository.findByInvocationId(invocationId);
            if (!records.isEmpty() && targetInvocationKey.equals(invocationKeyOfRecord(records.get(0)))) {
                businessMatches.add(invocationId);
            }
        }
        if (businessMatches.size() == 1) {
            out.println("提示：--调用点 " + filter + " 按 invocationKey 前缀匹配到 " + targetInvocationKey + "（业务标签 " + businessMatches.get(0) + "）");
            return businessMatches.get(0);
        }
        if (businessMatches.size() > 1) {
            throw new IllegalStateException("--invocation " + filter + " 对应调用点键覆盖多个业务标签：" + String.join(", ", businessMatches) + "，请使用确切的业务标签。");
        }
        return filter;
    }

    /**
     * 解析 --调用点 目标值（画像操作类命令用：approve/reject/rollback），返回唯一 invocationKey。
     * 解析优先级：完整 invocationKey 精确命中（即使它是其他 key 的前缀）＞ 业务标签（该标签
     * 覆盖多个分组时报错并列出）＞ invocationKey 唯一前缀。无命中或多命中均抛
     * {@link IllegalStateException}，由命令层转译为退出码 2。
     */
    static String resolveInvocationKeyTarget(StorageRepository repository, String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalStateException("缺少目标 调用点。");
        }
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (filter.equals(profile.getInvocationKey())) {
                return filter;
            }
        }
        Set<String> businessIds = recordedInvocationIds(repository);
        if (businessIds.contains(filter)) {
            Set<String> invocationKeys = new LinkedHashSet<>();
            for (InteractionRecord record : repository.findByInvocationId(filter)) {
                String invocationKey = invocationKeyOfRecord(record);
                if (invocationKey != null) {
                    invocationKeys.add(invocationKey);
                }
            }
            if (invocationKeys.isEmpty()) {
                throw new IllegalStateException("业务标签 " + filter + " 下没有可分组的录制记录，无法定位 调用点。");
            }
            if (invocationKeys.size() > 1) {
                throw new IllegalStateException("业务标签 " + filter + " 覆盖多个调用点：" + String.join(", ", invocationKeys) + "，请用 invocationKey（或其唯一前缀）指定。");
            }
            return invocationKeys.iterator().next();
        }
        List<InvocationProfile> prefixMatches = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getInvocationKey() != null && profile.getInvocationKey().startsWith(filter)) {
                prefixMatches.add(profile);
            }
        }
        if (prefixMatches.isEmpty()) {
            throw new IllegalStateException("没有匹配 " + filter + " 的调用点（业务标签或 invocationKey 前缀，完整列表见 status 命令）。");
        }
        if (prefixMatches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (InvocationProfile profile : prefixMatches) {
                keys.add(profile.getInvocationKey());
            }
            throw new IllegalStateException("前缀匹配到多个调用点：" + String.join(", ", keys) + "，请提供更长的前缀。");
        }
        return prefixMatches.get(0).getInvocationKey();
    }

    /**
     * 单条记录的分组键：优先用落库存储值（enrich 写入，录入即定格——存储键与
     * 现算键不得分叉），缺失时按分组器现算；无法分组的记录返回 null。
     */
    private static String invocationKeyOfRecord(InteractionRecord record) {
        if (record.getInvocationKey() != null && !record.getInvocationKey().isEmpty()) {
            return record.getInvocationKey();
        }
        try {
            return InvocationResolver.resolve(record).getInvocationKey();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 依据主配置 llm 段构造 OpenAI 兼容客户端——重放类命令共用
     * 同一构造（单一来源），端点/密钥/模型/重试/extraBody 口径不得分叉。
     */
    static LlmClient createLlmClient(AgentAssert4jConfig config) {
        return new OpenAiCompatibleClient(config.getLlm().getEndpoint(), config.getLlm().getApiKey(), config.getLlm().getModel(), OpenAiCompatibleClient.DEFAULT_MAX_RETRIES, config.getLlm().getExtraBody());
    }

    /**
     * 依据主配置 regression 段构造确定性对比器——重放类命令共用
     * 同一构造（单一来源），ignorableFields 口径不得分叉。
     */
    static DeterministicComparator createComparator(AgentAssert4jConfig config) {
        ComparatorConfig comparatorConfig = ComparatorConfig.defaults();
        comparatorConfig.setIgnorableFields(new HashSet<>(config.getRegression().getIgnorableFields()));
        return new DeterministicComparator(comparatorConfig);
    }

    /**
     * 全库记录按分组键分桶：桶按分组键字典序，桶内保持存储规范序（时间、seq、recordId）。
     * 选例与建档共用本枚举——形状派生组与声明组同权，不因「未声明」失去框架服务资格。
     */
    static Map<String, List<InteractionRecord>> invocationBuckets(StorageRepository repository) {
        Map<String, List<InteractionRecord>> buckets = new TreeMap<>();
        for (String sessionId : repository.findAllSessionIds()) {
            for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                String invocationKey = invocationKeyOfRecord(record);
                if (invocationKey == null) {
                    continue;
                }
                buckets.computeIfAbsent(invocationKey, k -> new ArrayList<>()).add(record);
            }
        }
        Comparator<InteractionRecord> canonical = Comparator.comparing(InteractionRecord::getTimestamp).thenComparing(InteractionRecord::getSeq).thenComparing(InteractionRecord::getRecordId);
        for (List<InteractionRecord> records : buckets.values()) {
            records.sort(canonical);
        }
        return buckets;
    }

    /**
     * 规则配置里出现未知 behavior 名时告警——未知名在判定中被静默视为通过，
     * 笔误（如 noErr 写成 noErr0）会让维度 4 满分化、CI 照绿，必须在加载时点破。
     */
    static void warnUnknownBehaviors(InvocationRulesConfig rules, PrintStream out) {
        for (String invocationId : rules.getDeclaredInvocationIds()) {
            warnUnknownBehaviors("调用点 " + invocationId, rules.getRulesForInvocation(invocationId), out);
        }
    }

    /**
     * 单条规则声明的未知 behavior 告警——owner 标明声明来源（调用点 标签或场景标识）。
     */
    static void warnUnknownBehaviors(String owner, InvocationRule rule, PrintStream out) {
        Set<String> unknown = unknownBehaviors(rule);
        if (!unknown.isEmpty()) {
            out.println("警告：" + owner + " 声明了未知行为 " + String.join(", ", unknown) + "（该规则将被忽略）。合法行为名：" + String.join(", ", new TreeSet<>(BehaviorChecker.getBuiltinBehaviorNames())));
        }
    }

    /**
     * 收集单条规则声明里未知的 behavior 名，空集 = 全部可识别。
     */
    static Set<String> unknownBehaviors(InvocationRule rule) {
        Set<String> builtins = BehaviorChecker.getBuiltinBehaviorNames();
        Set<String> unknown = new TreeSet<>();
        for (String behavior : rule.getBehaviors()) {
            if (!builtins.contains(behavior)) {
                unknown.add(behavior);
            }
        }
        return unknown;
    }
}
