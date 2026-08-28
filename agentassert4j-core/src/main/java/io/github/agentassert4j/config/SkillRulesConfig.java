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
 *   "skills": {
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
public class SkillRulesConfig {

    /**
     * skillId → SkillRule 映射
     */
    private final Map<String, SkillRule> rules = new HashMap<>();

    public SkillRulesConfig() {
    }

    /**
     * 从 JSON 字符串解析规则配置。解析失败时安全退化为空配置。
     *
     * @param json JSON 格式的规则文本
     * @return 解析后的规则配置
     */
    @SuppressWarnings("unchecked")
    public static SkillRulesConfig fromJson(String json) {
        SkillRulesConfig config = new SkillRulesConfig();
        if (TextUtil.isBlank(json)) return config;

        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) return config;

        Map<String, Object> root = (Map<String, Object>) parsed;
        Object skillsObj = root.get("skills");
        if (!(skillsObj instanceof Map)) return config;

        Map<String, Object> skillsMap = (Map<String, Object>) skillsObj;
        for (Map.Entry<String, Object> entry : skillsMap.entrySet()) {
            String skillId = entry.getKey();
            if (entry.getValue() instanceof Map) {
                config.rules.put(skillId, SkillRule.fromJson((Map<String, Object>) entry.getValue()));
            }
        }
        return config;
    }

    /**
     * 获取指定 Skill 的规则声明。无匹配时返回空规则（而非 null）。
     *
     * @param skillId Skill 标识
     * @return 规则声明（永不为 null）
     */
    public SkillRule getRulesForSkill(String skillId) {
        return rules.getOrDefault(skillId, SkillRule.EMPTY);
    }

    /**
     * 获取所有已声明的 Skill ID
     */
    public Set<String> getDeclaredSkillIds() {
        return Collections.unmodifiableSet(rules.keySet());
    }

    /**
     * 是否存在任何规则声明
     */
    public boolean hasRules() {
        return !rules.isEmpty();
    }

    void addRule(String skillId, SkillRule rule) {
        rules.put(skillId, rule);
    }

    /**
     * 单个 Skill 的规则声明 — 维度 3（内容规则）+ 维度 4（约束行为）。
     *
     * <p>不可变对象：集合字段构造后只读（空集合与解析产物均不可变），
     * 共享的 {@link #EMPTY} 因此可以安全复用。</p>
     */
    public static class SkillRule {

        /**
         * 空规则（无任何声明），全库共享
         */
        static final SkillRule EMPTY = new SkillRule();

        private final Set<String> requiredKeywords;
        private final Set<String> forbiddenKeywords;
        private final List<RegexPattern> regexPatterns;
        private final Set<String> behaviors;

        public SkillRule() {
            this(Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<RegexPattern>emptyList(), Collections.<String>emptySet());
        }

        private SkillRule(Set<String> requiredKeywords, Set<String> forbiddenKeywords, List<RegexPattern> regexPatterns, Set<String> behaviors) {
            this.requiredKeywords = requiredKeywords;
            this.forbiddenKeywords = forbiddenKeywords;
            this.regexPatterns = regexPatterns;
            this.behaviors = behaviors;
        }

        @SuppressWarnings("unchecked")
        static SkillRule fromJson(Map<String, Object> map) {
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

            return new SkillRule(req, forbid, patterns, behaviors);
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
    }
}
