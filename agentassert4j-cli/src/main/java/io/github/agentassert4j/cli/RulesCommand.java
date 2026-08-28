package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BehaviorChecker;
import picocli.CommandLine.Command;

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
@Command(name = "rules", description = "查看内置约束行为目录与规则文件写法", mixinStandardHelpOptions = true)
public class RulesCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        Set<String> builtins = new TreeSet<>(BehaviorChecker.getBuiltinBehaviorNames());
        System.out.println("内置约束行为（agentassert4j-rules.json 的 behaviors 字段可用的全部名称）:");
        for (String name : builtins) {
            System.out.println("  " + name + " — " + describe(name));
        }
        System.out.println();
        System.out.println("规则文件示例（agentassert4j-rules.json，与 agentassert4j.json 同目录查找）:");
        System.out.println("{");
        System.out.println("  \"skills\": {");
        System.out.println("    \"<业务 skillId>\": {");
        System.out.println("      \"requiredKeywords\": [\"订单\"],");
        System.out.println("      \"forbiddenKeywords\": [\"抱歉\"],");
        System.out.println("      \"regexPatterns\": [\"\\\\d{6,}\"],");
        System.out.println("      \"behaviors\": [\"mustUseChinese\", \"jsonOutput\"]");
        System.out.println("    }");
        System.out.println("  }");
        System.out.println("}");
        return 0;
    }

    private static String describe(String name) {
        switch (name) {
            case "mustUseChinese":
                return "输出含中文（多行安全）";
            case "mustUseEnglish":
                return "输出含拉丁字母且不含中文";
            case "containsCjk":
                return "输出含 CJK 字符（含日文假名）";
            case "jsonOutput":
                return "输出为 JSON 形态（{ 或 [ 开头）";
            case "nonEmptyOutput":
                return "输出非空";
            case "noError":
                return "本次交互未出现错误字段";
            case "returnsErrorCode":
                return "输出包含错误码字段";
            case "returnsEmptyOnError":
                return "出错时输出为空";
            default:
                return "见 core 的 BehaviorChecker javadoc";
        }
    }
}
