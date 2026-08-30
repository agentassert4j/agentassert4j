package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BehaviorChecker;
import picocli.CommandLine.Command;

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
@Command(name = "rules", description = "查看内置约束行为目录与规则文件写法", mixinStandardHelpOptions = true)
public class RulesCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Override
    public Integer call() {
        Set<String> builtins = new TreeSet<>(BehaviorChecker.getBuiltinBehaviorNames());
        out.println("内置约束行为（agentassert4j-rules.json 的 behaviors 字段可用的全部名称）:");
        for (String name : builtins) {
            out.println("  " + name + " — " + describe(name));
        }
        out.println();
        out.println("规则文件示例（agentassert4j-rules.json，与 agentassert4j.json 同目录查找）:");
        out.println("{");
        out.println("  \"invocations\": {");
        out.println("    \"<业务 invocationId>\": {");
        out.println("      \"requiredKeywords\": [\"订单\"],");
        out.println("      \"forbiddenKeywords\": [\"抱歉\"],");
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
