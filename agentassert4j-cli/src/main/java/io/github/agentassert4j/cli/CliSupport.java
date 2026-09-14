package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.cli.llm.AnthropicMessagesClient;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.cli.llm.OpenAiResponsesClient;
import io.github.agentassert4j.cli.llm.ProtocolRoutingLlmClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.InvocationRulesConfig.InvocationRule;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.LlmWireProtocol;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.DriftReport;
import io.github.agentassert4j.spi.InteractionQueryStore;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;

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
    static StorageRepository openRepository(String dbOverride, PrintStream out) {
        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        // 隐式查找链（cwd → home → classpath）命中了哪个文件必须就地披露——
        // 错误目录下运行时旧配置静默生效是最难查的排障黑洞
        String configSource = ConfigLoader.describeMainConfigSource();
        out.println(configSource != null ? "Config: " + configSource : "Config: no agentassert4j.json found; using built-in defaults.");
        String url = dbOverride != null ? dbOverride : config.getStorage().getUrl();
        StorageRepository repository = new SqliteStorageRepository(expandHome(url));
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
        return flat.length() <= budget ? flat : flat.substring(0, budget) + "...";
    }

    /**
     * 人读键形态：声明 → 标签@细分短形；骨架/模板/请求锚 → 短名@细分短形（8 位）。
     * 完整键只在 JSON 证据与巡检明细——选择器语义不受影响。标签以解码形展示
     * （键存储的是编码形），团队词汇表原样可读；分组器的 encodeComponent 只
     * 转义六个 ASCII 字符，绝大多数标签编码前后同形。
     */
    static String displayKey(String invocationKey) {
        if (invocationKey == null || invocationKey.isEmpty()) {
            return "(unresolved invocation)";
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
        ParameterValueTracer tracer = new ParameterValueTracer(new InMemoryDependencyGraph());
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
     * 统一调用点解析阶梯——「把 --invocation 值解析成调用点键集合」的单源实现，
     * target 族（accept/reject/rollback/replay 的目标语义）与 filter 族（establish/
     * status 的缩域语义）共享同一阶梯，仅多键策略不同。
     *
     * <p>阶梯（高档短路低档）：① 完整 invocationKey 精确命中（即使它是他键前缀）；
     * ② 业务标签 → 该标签下全部键（plural 允许多键扇出；singular 多键报错列候选）；
     * ③ 显示短形 → 直返键（不做键→标签往返——裂键下首记录几乎总在最老键上，往返
     * 是有损投影）；④ invocationKey 唯一前缀（多命中报错列候选）；⑤ 零命中 →
     * E-NO-DATA 响亮报错并列全部合法写法（不静默裸返回）。</p>
     *
     * <p>键空间 = 已录键全集（{@link #recordedInvocationFootprints}）——画像皆由记录
     * 建档且记录只追加，已录键是画像键的实践超集；未建档裂键同样可解析（W11.9a 的
     * 根因修复），target 族消费方的画像存在性由各自既有守卫承接。</p>
     *
     * @param filter 原始 --invocation 值（null/空 = 不缩域，返回 null）
     * @param plural true = filter 族策略（标签扇出全部键）；false = target 族（多键报错）
     * @param notice 换算提示行输出通道（null = 静默；--json 模式应传 err 保 stdout 纯净）
     * @return 解析出的键集合（恒非空、按解析序）
     */
    static List<String> resolveInvocationKeys(StorageRepository repository, String filter, boolean plural, PrintStream notice) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        List<String> recordedKeys = recordedInvocationKeys(repository);
        if (recordedKeys.contains(filter)) {
            return new ArrayList<>(Collections.singletonList(filter));
        }
        if (recordedInvocationIds(repository).contains(filter)) {
            Set<String> labelKeys = new LinkedHashSet<>();
            for (InteractionRecord record : repository.findByInvocationId(filter)) {
                String invocationKey = invocationKeyOfRecord(record);
                if (invocationKey != null) {
                    labelKeys.add(invocationKey);
                }
            }
            if (!labelKeys.isEmpty()) {
                if (labelKeys.size() > 1 && !plural) {
                    throw new CliFailureException(CliErrorCode.E_USAGE, "Business label " + filter + " covers multiple invocations: " + String.join(", ", labelKeys) + "; specify one with an invocationKey (unique prefix or the status display form).", "Pick one invocationKey (unique prefix or the status display form) from the listed candidates.", "");
                }
                return new ArrayList<>(labelKeys);
            }
        }
        String displayMatch = resolveByDisplayForm(repository, filter);
        if (displayMatch != null) {
            if (notice != null) {
                notice.println("Note: --invocation " + filter + " matched display form " + displayMatch);
            }
            return new ArrayList<>(Collections.singletonList(displayMatch));
        }
        List<String> prefixMatches = new ArrayList<>();
        for (String key : recordedKeys) {
            if (key.startsWith(filter)) {
                prefixMatches.add(key);
            }
        }
        if (prefixMatches.size() > 1) {
            throw new CliFailureException(CliErrorCode.E_USAGE, "Prefix matches multiple invocations: " + String.join(", ", prefixMatches) + "; provide a longer prefix.", "Provide a longer --invocation prefix so exactly one invocation matches.", "");
        }
        if (prefixMatches.size() == 1) {
            if (notice != null) {
                notice.println("Note: --invocation " + filter + " matched invocationKey prefix " + prefixMatches.get(0));
            }
            return prefixMatches;
        }
        throw new CliFailureException(CliErrorCode.E_NO_DATA, "No invocation matching " + filter + " (accepted: business label, invocationKey prefix, or the status display form like label@8hex; see `status` for the full list).", "Check the value against `status` output, then retry.", "agentassert4j status");
    }

    /**
     * 解析 --invocation 目标值（画像操作类命令用：accept/reject/rollback/replay），返回唯一
     * invocationKey——统一阶梯的 singular 策略。多命中抛 E-USAGE、零命中抛 E-NO-DATA 的
     * {@link CliFailureException}，由命令层转译为退出码 2 与机器包络；解析出的键无画像时由
     * 调用方的画像存在性守卫承接（已录未建档键可解析）。
     */
    static String resolveInvocationKeyTarget(StorageRepository repository, String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new CliFailureException(CliErrorCode.E_USAGE, "Missing invocation target.", "Pass --invocation with a business label, invocationKey prefix, or the status display form.", "");
        }
        return resolveInvocationKeys(repository, filter, false, null).get(0);
    }

    /**
     * 已录键全集（键字典序）——统一解析阶梯的扫描空间。与建档分桶
     * （{@link #invocationBuckets}）同键同源：存储键缺失时按解析器现算，
     * 解析出的键与 establish/status 的目标桶逐字一致。
     */
    private static List<String> recordedInvocationKeys(StorageRepository repository) {
        return new ArrayList<>(invocationBuckets(repository).keySet());
    }

    /**
     * 显示短形反解——status/对齐报告展示的「标签@8位哈希」「skl@8位」等短形可直接
     * 粘贴为 --invocation 值，消除「看得到的写法选不了」的文法分叉。匹配方式：对已录键
     * 现算显示形后全等比对（哈希段大小写不敏感）——已录键全集含未建档裂键，短形对
     * 未建档键同样可选；末段不是 8 位十六进制的值不视为显示短形，返回 null 由调用方走
     * 原解析路径。多命中（细分哈希前 8 位撞车）抛 E-USAGE 的 {@link CliFailureException}
     * 由命令层转译为退出码 2 与机器包络。
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
        List<String> matches = new ArrayList<>();
        for (String key : recordedInvocationKeys(repository)) {
            String shown = displayKey(key);
            if (shown != null && shown.length() == filter.length() && shown.startsWith(prefix) && shown.regionMatches(true, at + 1, hash, 0, 8)) {
                matches.add(key);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new CliFailureException(CliErrorCode.E_USAGE, "Display form " + filter + " matches multiple invocations (subdivision hash collision in the first 8 hex chars): " + String.join(", ", matches) + "; provide the full invocationKey.", "Provide the full invocationKey to disambiguate the hash collision.", "");
        }
        return matches.get(0);
    }

    /**
     * 全库任务链（跨会话，按链首时间升序）——任务域命令的统一派生入口。
     */
    static List<TaskChain> taskChains(StorageRepository repository) {
        return TaskChainView.resolveAll(repository);
    }

    /**
     * 多步零标签链判定（doctor 身份段与出口健康摘要共用的单一口径）：
     * 链内没有任何声明标签且步骤数 > 1——步骤可见性与任务规则都依赖标签。
     */
    static boolean isMultiStepUnlabeled(TaskChain chain) {
        boolean anyLabel = false;
        for (InteractionRecord record : chain.getRecords()) {
            if (record.getInvocationId() != null && !record.getInvocationId().isEmpty()) {
                anyLabel = true;
                break;
            }
        }
        return !anyLabel && chain.getRecords().size() > 1;
    }

    /**
     * 出口健康摘要 — 裂键/自建任务/多步零标签链三计数，doctor 行动价值的
     * 主流程出口压缩形态：计数来自与 doctor 同源的确定性事实，细节仍归 doctor。
     */
    static final class ExitHealth {
        final int labelSplits;
        final int selfEstablishedTasks;
        final int multiStepUnlabeledChains;

        ExitHealth(DriftReport drift, List<TaskChain> chains) {
            this.labelSplits = drift.getLabelSplits().size();
            Map<String, Integer> chainsByRequest = new LinkedHashMap<>();
            for (TaskChain chain : chains) {
                chainsByRequest.merge(chain.getRequestText(), 1, Integer::sum);
            }
            int selfEstablished = 0;
            int unlabeled = 0;
            for (TaskChain chain : chains) {
                if (chainsByRequest.get(chain.getRequestText()) == 1) {
                    selfEstablished++;
                }
                if (isMultiStepUnlabeled(chain)) {
                    unlabeled++;
                }
            }
            this.selfEstablishedTasks = selfEstablished;
            this.multiStepUnlabeledChains = unlabeled;
        }

        boolean any() {
            return labelSplits > 0 || selfEstablishedTasks > 0 || multiStepUnlabeledChains > 0;
        }

        /**
         * 人读一行；全零返回 null（无行动价值就不占一行）。
         */
        String humanLine() {
            if (!any()) {
                return null;
            }
            return "Health: " + plural(labelSplits, "label split") + ", " + plural(selfEstablishedTasks, "self-established task") + ", " + plural(multiStepUnlabeledChains, "multi-step unlabeled chain") + " — `agentassert4j doctor` breaks these down.";
        }

        /**
         * JSON 片段（含花括号），供 status/1、verify-report/1 与 exit-health 报告复用。
         */
        String jsonFragment() {
            return "{\"labelSplits\":" + labelSplits + ",\"selfEstablishedTasks\":" + selfEstablishedTasks + ",\"multiStepUnlabeledChains\":" + multiStepUnlabeledChains + "}";
        }
    }

    /**
     * 计数名词的原生单复数形态（"1 record" / "2 records"；零取复数是英文惯例）。
     */
    static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
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
     * 依据主配置 llm 段构造重放客户端——协议路由装配（显式配置 llm.protocol
     * 覆盖记录方言推导，见 ProtocolRoutingLlmClient），重放类命令共用同一构造
     * （单一来源），端点/密钥/模型/重试/extraBody 口径不得分叉。未知协议值在
     * 此抛用法错误并列全部合法值——配置错误就近可见，不静默回退。
     */
    static LlmClient createLlmClient(AgentAssert4jConfig config) {
        String protocol = config.getLlm().getProtocol();
        if (protocol != null && LlmWireProtocol.fromWireName(protocol) == null) {
            throw new CliFailureException(CliErrorCode.E_USAGE, "llm.protocol '" + protocol + "' is not a known wire protocol.", "Valid values: " + LlmWireProtocol.legalWireNames() + ".", "");
        }
        String endpoint = config.getLlm().getEndpoint();
        String apiKey = config.getLlm().getApiKey();
        String model = config.getLlm().getModel();
        String extraBody = config.getLlm().getExtraBody();
        int maxRetries = config.getLlm().getMaxRetries();
        return new ProtocolRoutingLlmClient(protocol, new OpenAiCompatibleClient(endpoint, apiKey, model, maxRetries, extraBody), new AnthropicMessagesClient(endpoint, apiKey, model, maxRetries, extraBody), new OpenAiResponsesClient(endpoint, apiKey, model, maxRetries, extraBody));
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
            warnUnknownBehaviors("invocation " + invocationId, rules.getRulesForInvocation(invocationId), out);
        }
    }

    /**
     * 单条规则声明的未知 behavior 告警——owner 标明声明来源（调用点标签）。
     */
    private static void warnUnknownBehaviors(String owner, InvocationRule rule, PrintStream out) {
        Set<String> unknown = unknownBehaviors(rule);
        if (!unknown.isEmpty()) {
            out.println("Warning: " + owner + " declares unknown behaviors: " + String.join(", ", unknown) + " (unknown names have no effect; they are treated as passing). Valid behavior names: " + String.join(", ", new TreeSet<>(BehaviorChecker.getBuiltinBehaviorNames())));
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
     * rules.tasks 段的畸形声明告警清单——畸形约束要么无约束力要么永不满足，
     * 静默存在会让团队纪律形同虚设或全部误报，必须在加载时点破。
     * 输出与机器通道（doctor/1 的 ruleWarnings）共用同一份清单，两通道同词。
     */
    static List<String> malformedTaskRuleWarnings(InvocationRulesConfig rules) {
        List<String> warnings = new ArrayList<>();
        for (String note : rules.getParseNotes()) {
            warnings.add("rules.tasks " + note + ".");
        }
        for (String taskKey : rules.getDeclaredTaskKeys()) {
            if (taskKey == null || taskKey.trim().isEmpty()) {
                warnings.add("rules.tasks has an empty task key declaration (never matches; use the taskKey declared at recording time).");
                continue;
            }
            InvocationRulesConfig.TaskRule rule = rules.getTaskRule(taskKey);
            if (rule.getRequiredSteps().isEmpty() && rule.getRequiredOrder().isEmpty() && rule.getSteps().isEmpty()) {
                warnings.add("task " + taskKey + " declares no constraints (requiredSteps/requiredOrder/steps all empty); the rule has no effect.");
            }
            if (!rule.getRequiredOrder().isEmpty() && rule.getRequiredOrder().stream().anyMatch(step -> step == null || step.trim().isEmpty())) {
                warnings.add("task " + taskKey + " has empty labels in requiredOrder.");
            }
            for (Map.Entry<String, InvocationRulesConfig.StepCount> entry : rule.getSteps().entrySet()) {
                InvocationRulesConfig.StepCount bounds = entry.getValue();
                if (bounds.isUnbounded()) {
                    warnings.add("task " + taskKey + " step " + entry.getKey() + " declares neither min nor max (no constraint).");
                } else if (bounds.getMin() != null && bounds.getMax() != null && bounds.getMin() > bounds.getMax()) {
                    warnings.add("task " + taskKey + " step " + entry.getKey() + " declares min(" + bounds.getMin() + ") > max(" + bounds.getMax() + ") (unsatisfiable; judged as all violations).");
                }
            }
        }
        return warnings;
    }

    /**
     * 规则告警就地输出（Warning: 前缀的人类通道形态）。
     */
    static void warnMalformedTaskRules(InvocationRulesConfig rules, PrintStream out) {
        for (String warning : malformedTaskRuleWarnings(rules)) {
            out.println("Warning: " + warning);
        }
    }

    /**
     * 命令失败统一出口：人类通道输出现象一行，--json 模式向 stdout 追加
     * agentassert4j.error/1 单行包络（stdout 恒为机器可读）。返回退出码 2。
     */
    static int fail(boolean jsonOutput, PrintStream out, PrintStream err, CliErrorCode errorCode, String message, String hint, String nextAction) {
        err.println(message);
        if (jsonOutput) {
            out.println(errorEnvelope(errorCode, message, hint, nextAction));
        }
        return 2;
    }

    /**
     * {@link CliFailureException} 的出口转译——错误码与指引在抛出点已钉死。
     */
    static int fail(boolean jsonOutput, PrintStream out, PrintStream err, CliFailureException e) {
        return fail(jsonOutput, out, err, e.errorCode, describe(e), e.hint, e.nextAction);
    }

    /**
     * agentassert4j.error/1 单行包络：机器消费方按 errorCode 分支、按 hints 自助续行。
     * hints 为失败路径必填项，空缺时以通用指引兜底。
     */
    static String errorEnvelope(CliErrorCode errorCode, String message, String hint, String nextAction) {
        String safeHint = hint == null || hint.isEmpty() ? "Fix the reported problem, then retry." : hint;
        return "{\"schema\":\"" + ReportSchemas.ERROR + "\",\"status\":\"error\",\"errorCode\":\"" + errorCode.wireName() + "\",\"message\":\"" + RecursiveJsonParser.escape(message != null ? message : "") + "\",\"hints\":[\"" + RecursiveJsonParser.escape(safeHint) + "\"],\"nextAction\":\"" + RecursiveJsonParser.escape(nextAction != null ? nextAction : "") + "\"}";
    }

    /**
     * 步骤级判定度量 JSON 片段（similarity + dims 五维 + 可选 summary）。task-report
     * 与 verify-report 两个报告面共用——维度词表与 contentRules 的合成规则只此一份，
     * 两个报告面对同一判定必须报出完全一致的度量形态。
     */
    static String comparisonMetricsFragment(ComparisonResult comparison) {
        StringBuilder sb = new StringBuilder();
        sb.append(",\"similarity\":").append(comparison.getScore());
        sb.append(",\"dims\":{\"toolSet\":").append(comparison.isToolCallMatch());
        sb.append(",\"paramTypes\":").append(comparison.isParamTypeMatch());
        sb.append(",\"outputStructure\":").append(comparison.isStructureMatch());
        sb.append(",\"contentRules\":").append(comparison.isKeywordMatch() && comparison.isRegexMatch());
        sb.append(",\"behaviors\":").append(comparison.isBehaviorMatch()).append("}");
        if (comparison.getSummary() != null) {
            sb.append(",\"summary\":\"").append(RecursiveJsonParser.escape(comparison.getSummary())).append('"');
        }
        return sb.toString();
    }

    /**
     * 异常的单行现象描述——message 缺席时退化为类名，包络与 stderr 不出 "null"。
     */
    static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
