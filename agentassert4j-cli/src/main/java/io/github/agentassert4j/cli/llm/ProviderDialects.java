package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.util.RecursiveJsonParser;

import java.io.InputStream;
import java.util.*;

/**
 * 供应商方言注册表 — 内置「发送即报错」参数的裁剪规则（数据驱动，非代码分支）。
 *
 * <p>规则随 jar 分发（provider-dialects.json），按模型名前缀匹配，加载失败按
 * 空表处理（退化不中断）。本表只删标准参数、从不注入；用户 llm.extraBody
 * 显式配置的优先级高于本表。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
final class ProviderDialects {

    private final List<Rule> rules;

    private ProviderDialects(List<Rule> rules) {
        this.rules = rules;
    }

    static ProviderDialects load() {
        InputStream in = ProviderDialects.class.getResourceAsStream("provider-dialects.json");
        if (in == null) {
            return empty();
        }
        try {
            Object parsed = RecursiveJsonParser.parse(readAll(in));
            if (!(parsed instanceof Map)) {
                return empty();
            }
            Object rulesJson = ((Map<?, ?>) parsed).get("rules");
            if (!(rulesJson instanceof List)) {
                return empty();
            }
            List<Rule> rules = new ArrayList<>();
            for (Object item : (List<?>) rulesJson) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Rule rule = parseRule((Map<?, ?>) item);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            return new ProviderDialects(Collections.unmodifiableList(rules));
        } catch (RuntimeException e) {
            // 规则文件损坏按空表处理：客户端保持标准行为，退化不中断
            return empty();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 模型命中裁剪规则时返回须省略的标准参数名集合；未命中返回空集。
     */
    Set<String> droppedParamsFor(String model) {
        if (model == null || model.isEmpty()) {
            return Collections.emptySet();
        }
        String lower = model.toLowerCase(Locale.ROOT);
        Set<String> dropped = null;
        for (Rule rule : rules) {
            for (String prefix : rule.modelPrefixes) {
                if (lower.startsWith(prefix)) {
                    if (dropped == null) {
                        dropped = new HashSet<>();
                    }
                    dropped.addAll(rule.dropParams);
                    break;
                }
            }
        }
        return dropped != null ? dropped : Collections.<String>emptySet();
    }

    private static Rule parseRule(Map<?, ?> json) {
        Object prefixes = json.get("matchModelPrefix");
        Object params = json.get("dropParams");
        if (!(prefixes instanceof List) || !(params instanceof List)) {
            return null;
        }
        List<String> modelPrefixes = new ArrayList<>();
        for (Object p : (List<?>) prefixes) {
            if (p != null && !String.valueOf(p).isEmpty()) {
                modelPrefixes.add(String.valueOf(p).toLowerCase(Locale.ROOT));
            }
        }
        Set<String> dropParams = new HashSet<>();
        for (Object p : (List<?>) params) {
            if (p != null && !String.valueOf(p).isEmpty()) {
                dropParams.add(String.valueOf(p).toLowerCase(Locale.ROOT));
            }
        }
        if (modelPrefixes.isEmpty() || dropParams.isEmpty()) {
            return null;
        }
        return new Rule(modelPrefixes, dropParams);
    }

    private static ProviderDialects empty() {
        return new ProviderDialects(Collections.<Rule>emptyList());
    }

    private static String readAll(InputStream in) {
        Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }

    private static final class Rule {
        private final List<String> modelPrefixes;
        private final Set<String> dropParams;

        private Rule(List<String> modelPrefixes, Set<String> dropParams) {
            this.modelPrefixes = modelPrefixes;
            this.dropParams = dropParams;
        }
    }
}
