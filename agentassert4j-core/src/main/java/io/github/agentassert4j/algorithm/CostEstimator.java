package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 成本估算 — 价格快照驱动的执行前预估与调用计价。
 *
 * <p>价格的权威来源是随 jar 分发的精选快照（model_prices.json，按主流模型族裁剪，
 * 发布前从 LiteLLM 的 MIT 价格库重新生成），键为模型族名，查找按最长包含
 * 匹配把带日期的变体归入族价。快照外的模型族经 {@code agentassert4j-prices.json}
 * 覆盖文件补充（查找链同规则文件，格式同快照，并集覆盖：同族改价、新族补充）。
 * 查不到的模型不做货币估算——价格只是 token 统计之上的装饰层，缺失时只报
 * token 消耗，不编造费用，也永不参与判定。</p>
 *
 * <p>两个入口共用同一张表：{@link #estimate} 用「假设 1000 输入
 * 500 输出 token」的固定规则做执行前预估文案；{@link #estimateCallCostUsd}
 * 用调用实际 token 数在捕获时刻计价，冻结进记录的成本列。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class CostEstimator {

    private static final Logger LOG = Logger.getLogger(CostEstimator.class.getName());

    /**
     * 预估规则的假设 token 量：单次调用 1000 输入 / 500 输出
     */
    private static final long PREVIEW_INPUT_TOKENS = 1000;
    private static final long PREVIEW_OUTPUT_TOKENS = 500;

    /**
     * 不可变价格表（模型族 → 单价 + 长度降序键）。覆盖文件可能被长驻进程存活期间
     * 修改，整表以单个不可变引用发布——读取方永远看到自洽的一对（表与键序）。
     */
    private static final class PriceTable {
        final Map<String, double[]> prices;
        final List<String> keysByLengthDesc;

        PriceTable(Map<String, double[]> prices) {
            this.prices = Collections.unmodifiableMap(prices);
            List<String> keys = new ArrayList<>(prices.keySet());
            keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
            this.keysByLengthDesc = Collections.unmodifiableList(keys);
        }
    }

    /**
     * 快照部分静态加载（随 jar 分发、进程生命周期内不变）。
     */
    private static final Map<String, double[]> SNAPSHOT_PRICES = loadSnapshot();

    /**
     * 当前生效价格表：快照 + 覆盖文件（按文件 mtime 热读——长驻 server 存活期间
     * 写入/修改的 agentassert4j-prices.json 必须对后续计价可见，静态一次加载曾让
     * 覆盖在黑盒视角等于不存在）。
     */
    private static volatile PriceTable table = new PriceTable(SNAPSHOT_PRICES);
    /**
     * 已加载覆盖的文件 mtime；null = 尚未加载过（含无覆盖文件的常态）
     */
    private static final Object OVERRIDE_LOCK = new Object();
    private static volatile Long loadedOverrideStamp;

    private CostEstimator() {
    }

    /**
     * 覆盖文件的 mtime 变化时（含从无到有、删除）重建生效价格表；快照恒为基底。
     * 覆盖路径每次刷新时重新解析——路径若在类初始化时解析一次，进程启动后才创建的
     * 覆盖文件将永久不可见（实测确认过的缺陷）。双检锁 + 不可变整表
     * 发布，读取方无锁。
     */
    private static void refreshOverridesIfChanged() {
        String overridePath = ConfigLoader.resolvePriceOverridesPath();
        long stamp = overridePath != null ? new File(overridePath).lastModified() : 0L;
        Long loaded = loadedOverrideStamp;
        if (loaded != null && loaded == stamp) {
            return;
        }
        synchronized (OVERRIDE_LOCK) {
            overridePath = ConfigLoader.resolvePriceOverridesPath();
            // 删除覆盖文件（路径解析为 null）同样是一类状态变化：必须重建回快照，
            // 否则已吸收的覆盖价会在文件删除后继续生效
            stamp = overridePath != null ? new File(overridePath).lastModified() : 0L;
            loaded = loadedOverrideStamp;
            if (loaded != null && loaded == stamp) {
                return;
            }
            Map<String, double[]> merged = new LinkedHashMap<>(SNAPSHOT_PRICES);
            if (overridePath != null) {
                String overrideJson = null;
                try {
                    overrideJson = ConfigLoader.loadPriceOverrides();
                } catch (RuntimeException e) {
                    // 显式 prices.path 不可读等加载失败必须降级为快照兜底并就近可见：
                    // 价格配置问题不允许穿透录制隔离边界而中断业务调用
                    LOG.log(Level.SEVERE, "Failed to load price overrides (agentassert4j-prices.json); falling back to the bundled snapshot.", e);
                }
                if (overrideJson != null) {
                    applyPriceOverrides(merged, overrideJson);
                }
            }
            table = new PriceTable(merged);
            loadedOverrideStamp = stamp;
        }
    }

    /**
     * 成本预估（单次模式）。
     *
     * <p>重放一条交互记录恰好发起一次 LLM 调用：多轮上下文（previousTurns）
     * 作为请求的一部分携带，不产生额外调用。模型不在价格快照中时只报调用
     * 次数、不编造费用——预估文案与捕获计价遵守同一条「无价格不出货币数」原则。</p>
     *
     * @param testCases 待测用例列表
     * @param model     模型名称
     * @return 预估字符串，如 "Estimated 12 API calls, approx. $0.0480 (model: gpt-4o)"
     */
    public static String estimate(List<InteractionRecord> testCases, String model) {
        int totalCalls = testCases.size();
        String calls = totalCalls + " API call" + (totalCalls == 1 ? "" : "s");
        // 与 dry-run 机器计划同口径：逐记录按其真实历史用量折算（预览常量只作
        // 无用量数据记录的兜底），人读行与 JSON 面数字不再各说各话
        boolean anyUsage = false;
        boolean costKnown = true;
        double recordedCost = 0;
        for (InteractionRecord record : testCases) {
            long in = record.getInputTokens();
            long out = record.getOutputTokens();
            if (in <= 0 && out <= 0) {
                in = PREVIEW_INPUT_TOKENS;
                out = PREVIEW_OUTPUT_TOKENS;
            } else {
                anyUsage = true;
            }
            Double cost = estimateCallCostUsd(record.getServedModel() != null ? record.getServedModel() : model, in, out);
            if (cost == null) {
                costKnown = false;
            } else {
                recordedCost += cost;
            }
        }
        if (!costKnown) {
            return String.format("Estimated %s (model %s not in the price snapshot; cost unknown)", calls, model);
        }
        String basis = anyUsage ? "based on recorded usage" : "no recorded usage; preview-sized estimate";
        return "Estimated " + calls + ", approx. " + formatUsd(recordedCost) + " (" + basis + "; model: " + model + ")";
    }

    /**
     * 费用文案：亚分金额保留 6 位小数（%.4f 会把 $0.000003 级的微额调用格式化成
     * $0.0000，信息丢失）；1 分以上 4 位足够。
     */
    public static String formatUsd(double usd) {
        return String.format(usd != 0 && Math.abs(usd) < 0.01 ? "$%.6f" : "$%.4f", usd);
    }

    /**
     * 按调用实际 token 量计价（美元）；模型不在价格快照中时返回 null。
     * 由捕获侧在调用时刻调用，结果冻结进记录的成本列。
     *
     * @param model        模型名称（优先 served 模型，回退请求模型）
     * @param inputTokens  输入 token 总量（归一规则，含供应商缓存语义的合成）
     * @param outputTokens 输出 token 量
     * @return 费用（美元）；模型无价格时 null
     */
    public static Double estimateCallCostUsd(String model, long inputTokens, long outputTokens) {
        double[] rates = ratesFor(model);
        if (rates == null) {
            return null;
        }
        return inputTokens * rates[0] + outputTokens * rates[1];
    }

    private static double[] ratesFor(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        refreshOverridesIfChanged();
        PriceTable current = table;
        String lower = model.toLowerCase(Locale.ROOT);
        double[] exact = current.prices.get(lower);
        if (exact != null) {
            return exact;
        }
        for (String key : current.keysByLengthDesc) {
            if (lower.contains(key)) {
                return current.prices.get(key);
            }
        }
        return null;
    }

    private static Map<String, double[]> loadSnapshot() {
        Map<String, double[]> prices = new LinkedHashMap<>();
        loadSnapshotInto(prices);
        return prices;
    }

    /**
     * 随 jar 分发的价格快照。缺席/损坏按空表处理，退化不中断。
     */
    private static void loadSnapshotInto(Map<String, double[]> prices) {
        InputStream in = CostEstimator.class.getResourceAsStream("model_prices.json");
        if (in == null) {
            return;
        }
        try {
            Object parsed = RecursiveJsonParser.parse(readAll(in));
            if (parsed instanceof Map) {
                parseInto((Map<?, ?>) parsed, prices);
            }
        } catch (RuntimeException e) {
            // 快照损坏等同缺席，退化不中断
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 用户覆盖文件（agentassert4j-prices.json）的并集覆盖：同族改价、新族补充。
     * 损坏的覆盖文件必须就近可见（SEVERE）而不是静默失效——用户写了价格文件却
     * 看不到生效，比没有文件更难排查。包级可见供合并语义的确定性验证。
     */
    static void applyPriceOverrides(Map<String, double[]> prices, String overrideJson) {
        if (overrideJson == null || overrideJson.trim().isEmpty()) {
            return;
        }
        Object parsed;
        try {
            parsed = RecursiveJsonParser.parse(overrideJson);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Price override file (agentassert4j-prices.json) is not valid JSON; ignoring it.");
            return;
        }
        if (!(parsed instanceof Map)) {
            LOG.log(Level.SEVERE, "Price override file (agentassert4j-prices.json) is not a JSON object; ignoring it.");
            return;
        }
        if (parseInto((Map<?, ?>) parsed, prices) == 0) {
            LOG.log(Level.SEVERE, "Price override file (agentassert4j-prices.json) contained no usable price rows; ignoring it.");
        }
    }

    /**
     * 把「模型族 → {input, output}」形态的 JSON 对象吸收进价格表，返回可用行数
     * （同族改价也计入）；下划线前缀键是元信息非价格行，缺 input/output 的行跳过。
     * 价格值不校验符号——覆盖文件由使用者自控，价格是装饰层、永不参与判定。
     */
    private static int parseInto(Map<?, ?> source, Map<String, double[]> prices) {
        int rows = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("_") || !(entry.getValue() instanceof Map)) {
                continue;
            }
            Double input = asDouble(((Map<?, ?>) entry.getValue()).get("input"));
            Double output = asDouble(((Map<?, ?>) entry.getValue()).get("output"));
            if (input != null && output != null) {
                prices.put(key.toLowerCase(Locale.ROOT), new double[]{input, output});
                rows++;
            }
        }
        return rows;
    }

    private static String readAll(InputStream in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read model_prices.json", e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

}
