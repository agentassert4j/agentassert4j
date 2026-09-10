package io.github.agentassert4j.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * mcp 命令 — standalone 进程即 MCP server（stdio）。
 *
 * <p>绑定单一数据库（--db，缺省走配置 storage.url），工具面见 McpTools（CLI 动词薄壳
 * + record 摄取）。协议输出走独立构造的 stdout 文件描述符流（UTF-8、单行 JSON、逐消息
 * flush）——绝不与 System.out 混用，工具执行产出经命令实例捕获流隔离。stdin EOF 或
 * 断管 = 客户端会话结束，静默退出 exit 0（server 无会话状态，状态全在 SQLite）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
@Command(name = "mcp", aliases = {"m"}, description = "Run as an MCP server over stdio: tools mirror the CLI verbs (check/diff/report/verify/doctor/graph/rules, establish/accept/reject/rollback/audit, member-check, re-drive) plus record ingestion and record viewing tools for non-Java stacks", mixinStandardHelpOptions = true)
public class McpCommand implements Callable<Integer> {

    // 诊断通道：--diag 时逐消息记 method/耗时；server 正常运行对本流零输出
    PrintStream err = System.err;

    // 可注入通道：包内测试以内存流替换；生产默认 stdin 与真实 stdout 文件描述符
    InputStream in = System.in;
    PrintStream protocolOut;

    @Option(names = {"--db"}, description = "SQLite database path the server binds to (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--diag"}, description = "Log each protocol message to stderr (method and elapsed time; for troubleshooting)")
    boolean diag;

    @Override
    public Integer call() {
        PrintStream protocol = protocolOut != null ? protocolOut : CliSupport.utf8PrintStream(new FileOutputStream(FileDescriptor.out));
        McpDispatcher dispatcher = new McpDispatcher(McpTools.tools(db), diag ? err : null);
        new StdioTransport(in, protocol, dispatcher).run();
        return 0;
    }
}
