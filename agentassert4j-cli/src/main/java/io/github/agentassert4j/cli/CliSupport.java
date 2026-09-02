package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.InvocationRulesConfig.InvocationRule;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CLI 支撑工具 — 存储打开、调用点 枚举、依赖图加载、--invocation 目标解析的共用逻辑。
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
     * 吞掉输出的流——建档等下游组件只认 PrintStream 通道，
     * --json 模式下用一次性丢弃流保住 stdout 的单行报告契约。
     */
    static PrintStream discardStream() {
        return new PrintStream(new ByteArrayOutputStream(), true);
    }

    /**
     * 不可见字符可见化——用户可控文本回显进报错与报告时，\r 这类不可见字符会静默改变
     * 匹配结果又不显示（终端里 \r 甚至把光标移回行首吞掉前面的文字），转义后差异一眼可辨。
     * 覆盖 ASCII 控制符、DEL 与 Unicode 格式字符（零宽空格/双向标记/软连字符等
     * FORMAT 类——同样静默改变 equals/startsWith 却在终端不可见）。
     */
    static String visibleText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            if (c == '\r') {
                sb.append("<CR>");
            } else if (c == '\n') {
                sb.append("<LF>");
            } else if (c == '\t') {
                sb.append("<TAB>");
            } else if (c < 0x20 || c == 0x7F || Character.getType(c) == Character.FORMAT) {
                sb.append("<U+").append(String.format("%04X", (int) c)).append(">");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 单行缩略——超长文本（请求原文、用户误当路径传入的提示词全文）回显进报错与
     * 采样行时压到预算内：连续空白折叠为单空格，超预算截断加省略号。
     * 只影响呈现，完整原文仍在数据与 JSON 证据里。
     */
    static String abbreviateText(String text, int budget) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= budget ? flat : flat.substring(0, budget) + "…";
    }

    /**
     * 人读键形态：声明 → 标签@细分短形；骨架/模板/请求锚 → 短名@细分短形（8 位）。
     * 完整键只在 JSON 证据与巡检明细——选择器语义不受影响。标签以解码形展示
     * （键存储的是编码形），团队词汇表原样可读；分组器的 encodeComponent 只
     * 转义六个 ASCII 字符，绝大多数标签编码前后同形。
     */
    static String displayKey(String invocationKey) {
        if (invocationKey == null || invocationKey.isEmpty()) {
            return "(未解析调用点)";
        }
        String[] segments = invocationKey.split(":");
        if ("invocation".equals(segments[0]) && segments.length >= 2) {
            String label = TaskAligner.declaredLabelOfKey(invocationKey);
            return (label != null ? label : segments[1]) + (segments.length >= 3 ? "@" + abbreviateHash(segments[2]) : "");
        }
        if ("skeleton".equals(segments[0]) && segments.length >= 2) {
            return "skl@" + abbreviateHash(segments[1]);
        }
        if ("template".equals(segments[0]) && segments.length >= 2) {
            return "tpl@" + abbreviateHash(segments[1]);
        }
        if ("adhoc".equals(segments[0])) {
            return segments.length >= 2 && !"no-anchor".equals(segments[1]) ? "adhoc@" + abbreviateHash(segments[1]) : "adhoc";
        }
        return invocationKey;
    }

    private static String abbreviateHash(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /**
     * 调用点足迹——按完整键分组的只读巡检视图（status 未建档段与 doctor 共用）。
     */
    static final class InvocationFootprint {

        final String invocationKey;
        final String label;
        final int recordCount;
        final String lastSessionId;

        InvocationFootprint(String invocationKey, String label, int recordCount, String lastSessionId) {
            this.invocationKey = invocationKey;
            this.label = label;
            this.recordCount = recordCount;
            this.lastSessionId = lastSessionId;
        }
    }

    /**
     * 全库按完整键分组的调用点足迹（键缺失记录不入组；最近会话取该键时间戳最大记录所在会话）。
     */
    static List<InvocationFootprint> recordedInvocationFootprints(StorageRepository repository) {
        Map<String, InteractionRecord> latestByKey = new LinkedHashMap<>();
        Map<String, String> labelByKey = new LinkedHashMap<>();
        Map<String, Integer> countByKey = new LinkedHashMap<>();
        for (String sessionId : repository.findAllSessionIds()) {
            for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                String key = record.getInvocationKey();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                countByKey.merge(key, 1, Integer::sum);
                String label = record.getInvocationId();
                if (label != null && !label.isEmpty()) {
                    labelByKey.putIfAbsent(key, label);
                }
                InteractionRecord latest = latestByKey.get(key);
                if (latest == null || isAfter(record, latest)) {
                    latestByKey.put(key, record);
                }
            }
        }
        List<InvocationFootprint> footprints = new ArrayList<>();
        for (Map.Entry<String, InteractionRecord> entry : latestByKey.entrySet()) {
            String key = entry.getKey();
            footprints.add(new InvocationFootprint(key, labelByKey.get(key), countByKey.get(key), entry.getValue().getSessionId()));
        }
        footprints.sort((a, b) -> a.invocationKey.compareTo(b.invocationKey));
        return footprints;
    }

    private static boolean isAfter(InteractionRecord candidate, InteractionRecord current) {
        if (candidate.getTimestamp() != current.getTimestamp()) {
            return candidate.getTimestamp() > current.getTimestamp();
        }
        return candidate.getRecordId().compareTo(current.getRecordId()) > 0;
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
     * 解析 --invocation 过滤值（选例类命令用：replay/baseline）。与某业务 invocationId
     * 精确相等时按原义使用；否则按显示短形或 invocationKey 唯一前缀定位调用点并换算回
     * 业务标签（画像上的 invocationId 是分组器派生的内部标识，与记录上的业务标签是两套
     * 体系）。完全无命中时原样返回，由调用方的「未找到用例」路径兜底。
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
        String displayMatch = resolveByDisplayForm(repository, filter);
        if (displayMatch != null) {
            List<String> labelMatches = businessLabelsForKey(repository, displayMatch);
            if (labelMatches.size() == 1) {
                out.println("提示：--invocation " + filter + " 按显示短形匹配到 " + displayMatch + "（业务标签 " + labelMatches.get(0) + "）");
                return labelMatches.get(0);
            }
            if (labelMatches.size() > 1) {
                throw new IllegalStateException("--invocation " + filter + " 对应调用点键覆盖多个业务标签：" + String.join(", ", labelMatches) + "，请使用确切的业务标签。");
            }
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
        List<String> businessMatches = businessLabelsForKey(repository, targetInvocationKey);
        if (businessMatches.size() == 1) {
            out.println("提示：--invocation " + filter + " 按 invocationKey 前缀匹配到 " + targetInvocationKey + "（业务标签 " + businessMatches.get(0) + "）");
            return businessMatches.get(0);
        }
        if (businessMatches.size() > 1) {
            throw new IllegalStateException("--invocation " + filter + " 对应调用点键覆盖多个业务标签：" + String.join(", ", businessMatches) + "，请使用确切的业务标签。");
        }
        return filter;
    }

    /**
     * 解析 --invocation 目标值（画像操作类命令用：approve/reject/rollback），返回唯一 invocationKey。
     * 解析优先级：完整 invocationKey 精确命中（即使它是其他 key 的前缀）＞ 业务标签（该标签
     * 覆盖多个分组时报错并列出）＞ 显示短形（status 展示的 标签@8位/skl@8位 等，看得到的写法
     * 选得到）＞ invocationKey 唯一前缀。无命中或多命中均抛 {@link IllegalStateException}，
     * 由命令层转译为退出码 2。
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
                throw new IllegalStateException("业务标签 " + filter + " 覆盖多个调用点：" + String.join(", ", invocationKeys) + "，请用 invocationKey（或其唯一前缀、或 status 显示短形）指定。");
            }
            return invocationKeys.iterator().next();
        }
        String displayMatch = resolveByDisplayForm(repository, filter);
        if (displayMatch != null) {
            return displayMatch;
        }
        List<InvocationProfile> prefixMatches = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getInvocationKey() != null && profile.getInvocationKey().startsWith(filter)) {
                prefixMatches.add(profile);
            }
        }
        if (prefixMatches.isEmpty()) {
            throw new IllegalStateException("没有匹配 " + filter + " 的调用点（可用：业务标签、invocationKey 前缀、status 显示短形如 标签@8位；完整列表见 status 命令）。");
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
     * 显示短形反解——status/对齐报告展示的「标签@8位哈希」「skl@8位」等短形可直接
     * 粘贴为 --invocation 值，消除「看得到的写法选不了」的文法分叉。匹配方式：对画像键
     * 现算显示形后全等比对（哈希段大小写不敏感）；末段不是 8 位十六进制的值不视为显示
     * 短形，返回 null 由调用方走原解析路径。多命中（细分哈希前 8 位撞车）抛
     * {@link IllegalStateException} 由命令层转译为退出码 2。
     */
    static String resolveByDisplayForm(StorageRepository repository, String filter) {
        int at = filter == null ? -1 : filter.lastIndexOf('@');
        if (at < 0 || filter.length() - at - 1 != 8) {
            return null;
        }
        String hash = filter.substring(at + 1);
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        String prefix = filter.substring(0, at + 1);
        List<InvocationProfile> matches = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            String shown = displayKey(profile.getInvocationKey());
            if (shown != null && shown.length() == filter.length() && shown.startsWith(prefix) && shown.regionMatches(true, at + 1, hash, 0, 8)) {
                matches.add(profile);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (InvocationProfile profile : matches) {
                keys.add(profile.getInvocationKey());
            }
            throw new IllegalStateException("显示短形 " + filter + " 对应多个调用点（细分哈希前 8 位撞车）：" + String.join(", ", keys) + "，请提供完整 invocationKey。");
        }
        return matches.get(0).getInvocationKey();
    }

    /**
     * 调用点键 → 业务标签反查：恰一个业务标签的首记录落在此键上时返回单元素列表，
     * 零个或多个返回原样由调用方处置。键前缀与显示短形两条解析路径共用同一实现，
     * 两侧语义不得分叉。
     */
    private static List<String> businessLabelsForKey(StorageRepository repository, String invocationKey) {
        List<String> matches = new ArrayList<>();
        for (String invocationId : recordedInvocationIds(repository)) {
            List<InteractionRecord> records = repository.findByInvocationId(invocationId);
            if (!records.isEmpty() && invocationKey.equals(invocationKeyOfRecord(records.get(0)))) {
                matches.add(invocationId);
            }
        }
        return matches;
    }

    /**
     * 全库任务链（跨会话，按链首时间升序）——任务域命令的统一派生入口。
     */
    static List<TaskChain> taskChains(StorageRepository repository) {
        return TaskChainView.resolveAll(repository);
    }

    /**
     * 单条记录的调用点键：优先用落库存储值（enrich 写入，录入即定格——存储键与
     * 现算键不得分叉），缺失时按解析器现算；无法解析的记录返回 null。
     */
    static String invocationKeyOfRecord(InteractionRecord record) {
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
     * 单条规则声明的未知 behavior 告警——owner 标明声明来源（调用点标签）。
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

    /**
     * rules.tasks 段的畸形声明告警——畸形约束要么无约束力要么永不满足，
     * 静默存在会让团队纪律形同虚设或全部误报，必须在加载时点破。
     */
    static void warnMalformedTaskRules(InvocationRulesConfig rules, PrintStream out) {
        for (String note : rules.getParseNotes()) {
            out.println("警告：rules.tasks " + note + "。");
        }
        for (String taskKey : rules.getDeclaredTaskKeys()) {
            if (taskKey == null || taskKey.trim().isEmpty()) {
                out.println("警告：rules.tasks 存在空任务键声明（该规则永不匹配，请改用录制时声明的 taskKey）。");
                continue;
            }
            InvocationRulesConfig.TaskRule rule = rules.getTaskRule(taskKey);
            if (rule.getRequiredSteps().isEmpty() && rule.getRequiredOrder().isEmpty() && rule.getSteps().isEmpty()) {
                out.println("警告：任务 " + taskKey + " 未声明任何约束（requiredSteps/requiredOrder/steps 全空），该规则无约束力。");
            }
            if (!rule.getRequiredOrder().isEmpty() && rule.getRequiredOrder().stream().anyMatch(step -> step == null || step.trim().isEmpty())) {
                out.println("警告：任务 " + taskKey + " 的 requiredOrder 含空标签。");
            }
            for (Map.Entry<String, InvocationRulesConfig.StepCount> entry : rule.getSteps().entrySet()) {
                InvocationRulesConfig.StepCount bounds = entry.getValue();
                if (bounds.isUnbounded()) {
                    out.println("警告：任务 " + taskKey + " 的步骤 " + entry.getKey() + " 声明了 min/max 双缺（无约束力）。");
                } else if (bounds.getMin() != null && bounds.getMax() != null && bounds.getMin() > bounds.getMax()) {
                    out.println("警告：任务 " + taskKey + " 的步骤 " + entry.getKey() + " 声明 min(" + bounds.getMin() + ") > max(" + bounds.getMax() + ")（永不满足，判为全违规）。");
                }
            }
        }
    }
}
