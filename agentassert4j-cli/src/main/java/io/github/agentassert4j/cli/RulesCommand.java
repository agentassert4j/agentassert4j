package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BehaviorChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * rules 命令 — 展示内置约束行为目录与规则文件的写法入口。
 *
 * <p>合法 behavior 名以 BehaviorChecker 的内置库为唯一真源，这里只补
 * 面向用户的语义说明；目录不展示就没人知道 --rules.json 里 behaviors
 * 能写什么，写错的名字会被静默忽略（加载时有告警，但预防优于纠错）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
@Command(name = "rules", aliases = {"ru"}, description = "Show the built-in constraint behavior catalog and rules file examples", mixinStandardHelpOptions = true)
public class RulesCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--json"}, description = "Print the behavior catalog as a single-line JSON to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        Set<String> builtins = new TreeSet<>(BehaviorChecker.getBuiltinBehaviorNames());
        if (jsonOutput) {
            StringBuilder items = new StringBuilder();
            for (String name : builtins) {
                if (items.length() > 0) items.append(",");
                items.append("{\"name\":\"").append(name).append("\",\"description\":\"").append(describe(name)).append("\"}");
            }
            out.println("{\"schema\":\"agentassert4j.rules/1\",\"behaviors\":[" + items + "]}");
            return 0;
        }
        out.println("Built-in constraint behaviors (all names accepted by the behaviors field in agentassert4j-rules.json):");
        for (String name : builtins) {
            out.println("  " + name + " — " + describe(name));
        }
        out.println();
        out.println("Rules file example (agentassert4j-rules.json, looked up next to agentassert4j.json):");
        out.println("{");
        out.println("  \"invocations\": {");
        out.println("    \"<business invocationId>\": {");
        out.println("      \"requiredKeywords\": [\"order\"],");
        out.println("      \"forbiddenKeywords\": [\"sorry\"],");
        out.println("      \"regexPatterns\": [\"\\\\d{6,}\"],");
        out.println("      \"behaviors\": [\"mustUseChinese\", \"jsonOutput\"]");
        out.println("    }");
        out.println("  }");
        out.println("}");
        return 0;
    }

    private static String describe(String name) {
        switch (name) {
            case "mustUseChinese":
                return "output contains Chinese characters (multiline-safe)";
            case "mustUseEnglish":
                return "output contains Latin letters and no Chinese characters";
            case "containsCjk":
                return "output contains CJK characters (including Japanese kana)";
            case "jsonOutput":
                return "output is JSON-shaped (starts with { or [)";
            case "nonEmptyOutput":
                return "output is not empty";
            case "noError":
                return "no error field appeared in this interaction";
            case "returnsErrorCode":
                return "output contains an error-code field";
            case "returnsEmptyOnError":
                return "output is empty on error";
            default:
                return "see the BehaviorChecker javadoc in core";
        }
    }
}
