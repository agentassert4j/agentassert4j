package io.github.agentassert4j.config;

import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;

import java.util.*;

/**
 * 声明式规则配置 — 从 agentassert4j-rules.json 加载。
 *
 * <p>维度 3（内容规则）和维度 4（约束行为）从用户声明的规则配置加载，
 * 而非自动提取。类比 JUnit：JUnit 不自动推断断言，开发者手写 assertEquals；
 * AgentAssert4j 不自动推断规则，开发者在这里声明。</p>
 *
 * <h3>配置文件格式示例（agentassert4j-rules.json）</h3>
 * <pre>
 * {
 *   "invocations": {
 *     "queryOrderDB": {
 *       "requiredKeywords": ["订单号", "金额"],
 *       "forbiddenKeywords": ["密码"],
 *       "regexPatterns": [
 *         {"pattern": "\\d{4}-\\d{2}-\\d{2}", "description": "日期格式"}
 *       ],
 *       "behaviors": ["returnsEmptyOnInvalid"]
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>规则加载优先级：agentassert4j-rules.json > agentassert4j.json 内联规则 > 默认空</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InvocationRulesConfig {

    /**
     * invocationId → InvocationRule 映射
     */
    private final Map<String, InvocationRule> rules = new HashMap<>();

    /**
     * 声明 taskKey → TaskRule 映射（声明序，键必须来自任务声明而非请求原文）
     */
    private final Map<String, TaskRule> taskRules = new LinkedHashMap<>();

    /**
     * 解析注记：畸形声明（类型错值等）被安全忽略时在此留痕，加载侧据此告警——
     * 静默弱化约束会让团队纪律形同虚设（退化不中断，但必须可见）
     */
    private final List<String> parseNotes = new ArrayList<>();

    public InvocationRulesConfig() {
    }

    /**
     * 从 JSON 字符串解析规则配置。解析失败时安全退化为空配置。
     *
     * @param json JSON 格式的规则文本
     * @return 解析后的规则配置
     */
    public static InvocationRulesConfig fromJson(String json) {
        InvocationRulesConfig config = new InvocationRulesConfig();
        if (TextUtil.isBlank(json)) return config;

        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) return config;

        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) parsed;
        config.loadFromMap(root);
        return config;
    }

    /**
     * 从规则 JSON 的 Map 形态重建配置（与 {@link #toMap} 对称）——验收包内嵌的
     * 声明规则段由此还原，使验收侧与开发侧以同一份规则对称评估维度 3/4。
     * null/非 Map 输入安全退化为空配置。
     *
     * @param root 规则声明 Map（invocations/tasks 两段，形态同 agentassert4j-rules.json）
     * @return 重建的规则配置
     */
    public static InvocationRulesConfig fromMap(Map<String, Object> root) {
        InvocationRulesConfig config = new InvocationRulesConfig();
        config.loadFromMap(root);
        return config;
    }

    @SuppressWarnings("unchecked")
    private void loadFromMap(Map<String, Object> root) {
        if (root == null) return;
        Object invocationsObj = root.get("invocations");
        if (invocationsObj instanceof Map) {
            Map<String, Object> invocationsMap = (Map<String, Object>) invocationsObj;
            for (Map.Entry<String, Object> entry : invocationsMap.entrySet()) {
                String invocationId = entry.getKey();
                if (entry.getValue() instanceof Map) {
                    this.rules.put(invocationId, InvocationRule.fromJson((Map<String, Object>) entry.getValue()));
                }
            }
        }
        Object tasksObj = root.get("tasks");
        if (tasksObj instanceof Map) {
            Map<String, Object> tasksMap = (Map<String, Object>) tasksObj;
            for (Map.Entry<String, Object> entry : tasksMap.entrySet()) {
                String taskKey = entry.getKey();
                if (entry.getValue() instanceof Map) {
                    this.taskRules.put(taskKey, TaskRule.fromJson((Map<String, Object>) entry.getValue(), taskKey, this.parseNotes));
                } else {
                    this.parseNotes.add("task " + taskKey + " declaration is not an object; ignored entirely");
                }
            }
        }
    }

    /**
     * 序列化为规则声明 Map（与 {@link #fromMap} 对称）——随验收包出境的声明规则段，
     * 形态同 agentassert4j-rules.json 的 invocations/tasks 两段；空段省略键。
     * 只含声明内容（断言），不含任何录制原文。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        if (!rules.isEmpty()) {
            Map<String, Object> invocations = new LinkedHashMap<>();
            for (Map.Entry<String, InvocationRule> entry : rules.entrySet()) {
                invocations.put(entry.getKey(), entry.getValue().toMap());
            }
            root.put("invocations", invocations);
        }
        if (!taskRules.isEmpty()) {
            Map<String, Object> tasks = new LinkedHashMap<>();
            for (Map.Entry<String, TaskRule> entry : taskRules.entrySet()) {
                tasks.put(entry.getKey(), entry.getValue().toMap());
            }
            root.put("tasks", tasks);
        }
        return root;
    }

    /**
     * 解析注记（不可变视图）：畸形声明被安全忽略处的留痕，供加载侧告警
     */
    public List<String> getParseNotes() {
        return Collections.unmodifiableList(parseNotes);
    }

    /**
     * 获取指定调用点的规则声明。无匹配时返回空规则（而非 null）。
     *
     * @param invocationId 调用点声明标签
     * @return 规则声明（永不为 null）
     */
    public InvocationRule getRulesForInvocation(String invocationId) {
        return rules.getOrDefault(invocationId, InvocationRule.EMPTY);
    }

    /**
     * 获取所有已声明的调用点标签
     */
    public Set<String> getDeclaredInvocationIds() {
        return Collections.unmodifiableSet(rules.keySet());
    }

    /**
     * 是否存在任何规则声明
     */
    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /**
     * 获取指定声明任务键的任务规则。无匹配时返回空规则（而非 null）。
     *
     * @param declaredTaskKey 任务声明 taskKey（派生请求文本不作键）
     * @return 任务规则（永不为 null）
     */
    public TaskRule getTaskRule(String declaredTaskKey) {
        TaskRule rule = taskRules.get(declaredTaskKey);
        return rule != null ? rule : TaskRule.EMPTY;
    }

    /**
     * 获取所有已配置任务规则的任务键（声明序）
     */
    public Set<String> getDeclaredTaskKeys() {
        return Collections.unmodifiableSet(taskRules.keySet());
    }

    /**
     * 是否存在任何任务规则声明
     */
    public boolean hasTaskRules() {
        return !taskRules.isEmpty();
    }

    /**
     * 单个调用点的规则声明 — 维度 3（内容规则）+ 维度 4（约束行为）。
     *
     * <p>不可变对象：集合字段构造后只读（空集合与解析产物均不可变），
     * 共享的 {@link #EMPTY} 因此可以安全复用。</p>
     */
    public static class InvocationRule {

        /**
         * 空规则（无任何声明），全库共享
         */
        static final InvocationRule EMPTY = new InvocationRule();

        private final Set<String> requiredKeywords;
        private final Set<String> forbiddenKeywords;
        private final List<RegexPattern> regexPatterns;
        private final Set<String> behaviors;

        public InvocationRule() {
            this(Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<RegexPattern>emptyList(), Collections.<String>emptySet());
        }

        private InvocationRule(Set<String> requiredKeywords, Set<String> forbiddenKeywords, List<RegexPattern> regexPatterns, Set<String> behaviors) {
            this.requiredKeywords = requiredKeywords;
            this.forbiddenKeywords = forbiddenKeywords;
            this.regexPatterns = regexPatterns;
            this.behaviors = behaviors;
        }

        @SuppressWarnings("unchecked")
        static InvocationRule fromJson(Map<String, Object> map) {
            if (map == null) return EMPTY;

            // requiredKeywords
            Set<String> req = Collections.emptySet();
            Object reqObj = map.get("requiredKeywords");
            if (reqObj instanceof List) {
                req = toStringSet((List<?>) reqObj);
            }

            // forbiddenKeywords
            Set<String> forbid = Collections.emptySet();
            Object forbidObj = map.get("forbiddenKeywords");
            if (forbidObj instanceof List) {
                forbid = toStringSet((List<?>) forbidObj);
            }

            // regexPatterns
            List<RegexPattern> patterns = Collections.emptyList();
            Object regexObj = map.get("regexPatterns");
            if (regexObj instanceof List) {
                patterns = toRegexPatterns((List<?>) regexObj);
            }

            // behaviors
            Set<String> behaviors = Collections.emptySet();
            Object behObj = map.get("behaviors");
            if (behObj instanceof List) {
                behaviors = toStringSet((List<?>) behObj);
            }

            return new InvocationRule(req, forbid, patterns, behaviors);
        }

        private static Set<String> toStringSet(List<?> list) {
            Set<String> set = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null) set.add(String.valueOf(item));
            }
            return Collections.unmodifiableSet(set);
        }

        @SuppressWarnings("unchecked")
        private static List<RegexPattern> toRegexPatterns(List<?> list) {
            List<RegexPattern> patterns = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String pattern = m.get("pattern") != null ? String.valueOf(m.get("pattern")) : null;
                    String desc = m.get("description") != null ? String.valueOf(m.get("description")) : "";
                    if (pattern != null) {
                        patterns.add(new RegexPattern(pattern, desc));
                    }
                }
            }
            return Collections.unmodifiableList(patterns);
        }

        public Set<String> getRequiredKeywords() {
            return requiredKeywords;
        }

        public Set<String> getForbiddenKeywords() {
            return forbiddenKeywords;
        }

        public List<RegexPattern> getRegexPatterns() {
            return regexPatterns;
        }

        public Set<String> getBehaviors() {
            return behaviors;
        }

        /**
         * 序列化为声明 Map（缺省空段省略键）——与 {@link #fromJson(Map)} 对称
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!requiredKeywords.isEmpty()) {
                map.put("requiredKeywords", new ArrayList<>(requiredKeywords));
            }
            if (!forbiddenKeywords.isEmpty()) {
                map.put("forbiddenKeywords", new ArrayList<>(forbiddenKeywords));
            }
            if (!regexPatterns.isEmpty()) {
                List<Object> patterns = new ArrayList<>();
                for (RegexPattern pattern : regexPatterns) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("pattern", pattern.getPattern());
                    if (pattern.getDescription() != null && !pattern.getDescription().isEmpty()) {
                        p.put("description", pattern.getDescription());
                    }
                    patterns.add(p);
                }
                map.put("regexPatterns", patterns);
            }
            if (!behaviors.isEmpty()) {
                map.put("behaviors", new ArrayList<>(behaviors));
            }
            return map;
        }
    }

    /**
     * 单个任务链的纪律声明 — 必备步骤、有序子序列、步骤次数范围。
     *
     * <p>键是任务声明 taskKey（只对声明任务生效，派生请求文本不作键）；
     * 步骤指称是调用点声明标签（invocationId），匹配按精确相等。
     * 不可变对象：集合字段构造后只读，共享的 {@link #EMPTY} 安全复用。</p>
     */
    public static class TaskRule {

        /**
         * 空任务规则（无任何声明），全库共享
         */
        static final TaskRule EMPTY = new TaskRule(Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String, StepCount>emptyMap());

        private final List<String> requiredSteps;
        private final List<String> requiredOrder;
        private final Map<String, StepCount> steps;

        public TaskRule(List<String> requiredSteps, List<String> requiredOrder, Map<String, StepCount> steps) {
            this.requiredSteps = immutableList(requiredSteps);
            this.requiredOrder = immutableList(requiredOrder);
            this.steps = immutableSteps(steps);
        }

        /**
         * 是否无任何声明（必备步/顺序/次数全空）
         */
        public boolean isEmpty() {
            return requiredSteps.isEmpty() && requiredOrder.isEmpty() && steps.isEmpty();
        }

        /**
         * 必备步骤标签：链内至少出现一次，顺序不约束
         */
        public List<String> getRequiredSteps() {
            return requiredSteps;
        }

        /**
         * 有序子序列声明（含存在性）：该标签序列须按此相对顺序出现在链中
         */
        public List<String> getRequiredOrder() {
            return requiredOrder;
        }

        /**
         * 步骤次数范围：标签 → 绝对次数声明（对链内出现计数直接判定）
         */
        public Map<String, StepCount> getSteps() {
            return steps;
        }

        /**
         * 序列化为声明 Map（空段/缺省边界省略键）——与 {@link #fromJson(Map, String, List)} 对称
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!requiredSteps.isEmpty()) {
                map.put("requiredSteps", new ArrayList<>(requiredSteps));
            }
            if (!requiredOrder.isEmpty()) {
                map.put("requiredOrder", new ArrayList<>(requiredOrder));
            }
            if (!steps.isEmpty()) {
                Map<String, Object> stepsMap = new LinkedHashMap<>();
                for (Map.Entry<String, StepCount> entry : steps.entrySet()) {
                    Map<String, Object> bounds = new LinkedHashMap<>();
                    if (entry.getValue().getMin() != null) {
                        bounds.put("min", entry.getValue().getMin());
                    }
                    if (entry.getValue().getMax() != null) {
                        bounds.put("max", entry.getValue().getMax());
                    }
                    stepsMap.put(entry.getKey(), bounds);
                }
                map.put("steps", stepsMap);
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        static TaskRule fromJson(Map<String, Object> map, String taskKey, List<String> notes) {
            if (map == null) return EMPTY;

            List<String> requiredSteps = toStringList(map.get("requiredSteps"));
            List<String> requiredOrder = toStringList(map.get("requiredOrder"));
            Map<String, StepCount> steps = new LinkedHashMap<>();
            Object stepsObj = map.get("steps");
            if (stepsObj instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) stepsObj).entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) entry.getValue();
                        steps.put(entry.getKey(), new StepCount(asInteger(m.get("min"), taskKey, entry.getKey(), "min", notes), asInteger(m.get("max"), taskKey, entry.getKey(), "max", notes)));
                    } else {
                        notes.add("task " + taskKey + " step " + entry.getKey() + " declaration is not an object; ignored entirely");
                    }
                }
            }
            return new TaskRule(requiredSteps, requiredOrder, steps);
        }

        private static List<String> toStringList(Object value) {
            if (!(value instanceof List)) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null) result.add(String.valueOf(item));
            }
            return result;
        }

        /**
         * 边界值严格解析：仅接受整数值；缺省/类型错值返回 null（= 未声明），
         * 类型错值留解析注记供加载侧告警——静默弱化约束等于纪律失效
         */
        private static Integer asInteger(Object value, String taskKey, String step, String bound, List<String> notes) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                Number number = (Number) value;
                double d = number.doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return number.intValue();
                }
                notes.add("task " + taskKey + " step " + step + " has a non-integer " + bound + " (" + value + "); ignored");
                return null;
            }
            notes.add("task " + taskKey + " step " + step + " has a non-numeric " + bound + " (" + value + "); ignored");
            return null;
        }

        private static List<String> immutableList(List<String> source) {
            return Collections.unmodifiableList(new ArrayList<>(source));
        }

        private static Map<String, StepCount> immutableSteps(Map<String, StepCount> source) {
            LinkedHashMap<String, StepCount> copy = new LinkedHashMap<>();
            for (Map.Entry<String, StepCount> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 单个步骤标签的绝对次数声明 — min/max 均可省略（缺 min=0、缺 max=不设上限）。
     */
    public static class StepCount {

        private final Integer min;
        private final Integer max;

        public StepCount(Integer min, Integer max) {
            this.min = min;
            this.max = max;
        }

        public Integer getMin() {
            return min;
        }

        public Integer getMax() {
            return max;
        }

        /**
         * 是否声明了任何边界（min/max 双缺 = 无约束力，加载侧据此告警）
         */
        public boolean isUnbounded() {
            return min == null && max == null;
        }

        public boolean outOfRange(int count) {
            return (min != null && count < min) || (max != null && count > max);
        }
    }
}
