package io.github.agentassert4j.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器 — 从 classpath / 文件系统加载 JSON 配置并解析。
 *
 * <p>加载优先级（从高到低）：
 * <ol>
 *   <li>系统属性 {@code agentassert4j.config.path}（显式指定配置文件路径）</li>
 *   <li>当前目录下的 {@code agentassert4j.json}</li>
 *   <li>用户主目录下的 {@code ~/.agentassert4j/agentassert4j.json}</li>
 *   <li>Classpath 下的 {@code agentassert4j.json}</li>
 *   <li>默认值（安全退化 R10）</li>
 * </ol>
 *
 * <p>环境变量替换：支持 {@code ${ENV_VAR}} 语法，未设置的环境变量替换为空字符串。</p>
 */
public final class ConfigLoader {

    /** 主配置文件名 */
    public static final String MAIN_CONFIG_FILE = "agentassert4j.json";
    /** 规则配置文件名 */
    public static final String RULES_CONFIG_FILE = "agentassert4j-rules.json";
    /** 系统属性键：显式配置路径 */
    public static final String CONFIG_PATH_PROPERTY = "agentassert4j.config.path";
    /** 规则配置路径系统属性键 */
    public static final String RULES_PATH_PROPERTY = "agentassert4j.rules.path";

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)\\}");

    private ConfigLoader() {}

    /**
     * 加载主配置。按优先级搜索配置文件，解析失败时安全退化为默认值。
     *
     * @return 主配置（永不为 null）
     */
    public static AgentAssert4jConfig loadAgentAssert4jConfig() {
        String json = findAndRead(MAIN_CONFIG_FILE, CONFIG_PATH_PROPERTY);
        if (json != null) {
            json = resolveEnvVars(json);
        }
        return AgentAssert4jConfig.fromJson(json);
    }

    /**
     * 加载规则配置。按优先级搜索配置文件，解析失败时安全退化为空配置。
     *
     * @return 规则配置（永不为 null）
     */
    public static SkillRulesConfig loadRulesConfig() {
        String json = findAndRead(RULES_CONFIG_FILE, RULES_PATH_PROPERTY);
        if (json != null) {
            json = resolveEnvVars(json);
        }
        return SkillRulesConfig.fromJson(json);
    }

    /**
     * 替换字符串中的环境变量引用。
     * {@code ${ENV_VAR}} → 环境变量值，未设置时替换为空字符串。
     *
     * @param text 可能包含 ${ENV_VAR} 的文本
     * @return 替换后的文本
     */
    public static String resolveEnvVars(String text) {
        if (text == null) return null;
        Matcher matcher = ENV_VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = System.getenv(varName);
            matcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从 classpath 加载资源文件内容。
     *
     * @param filename 资源文件名
     * @return 文件内容，未找到返回 null
     */
    public static String loadFromClasspath(String filename) {
        try (var is = ConfigLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从文件系统加载文件内容。
     *
     * @param path 文件路径
     * @return 文件内容，未找到或读取失败返回 null
     */
    public static String loadFromFile(String path) {
        if (path == null) return null;
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 按优先级搜索并读取配置文件。
     * 1. 系统属性指定的路径
     * 2. 当前工作目录
     * 3. 用户主目录下的 .agentassert4j/
     * 4. Classpath
     */
    private static String findAndRead(String filename, String pathProperty) {
        // 1. 系统属性指定的路径
        String explicitPath = System.getProperty(pathProperty);
        if (explicitPath != null) {
            String content = loadFromFile(explicitPath);
            if (content != null) return content;
        }

        // 2. 当前工作目录
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            String content = loadFromFile(Paths.get(cwd, filename).toString());
            if (content != null) return content;
        }

        // 3. 用户主目录下的 .agentassert4j/
        String home = System.getProperty("user.home");
        if (home != null) {
            String content = loadFromFile(Paths.get(home, ".agentassert4j", filename).toString());
            if (content != null) return content;
        }

        // 4. Classpath
        return loadFromClasspath(filename);
    }
}
