package io.github.agentassert4j.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * stdio 传输 — 换行分隔 framing、UTF-8、行预算与通道纪律。
 *
 * <p>server 的 stdout 只写协议消息（单行 JSON + 换行，逐消息 flush）——写失败即客户端
 * 已断开，静默收摊；stdin EOF 同为正常生命周期终点。CRLF 行尾容忍（裁掉尾随 CR）；
 * 空行跳过不回应；超行预算的行排空至换行后按 invalid request 回应（防超大行拖垮内存，
 * record 携带 base64 多模态可达 MB 级，预算取 2 000 万字符留足余量）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class StdioTransport {

    /**
     * 消息处理器：正常消息返回应答行（null = 无应答，如通知）；framing 级失败
     * （超预算）由 malformedResponse 给出 id:null 的错误应答。
     */
    interface MessageHandler {
        String handle(String line);

        String malformedResponse(String reason);
    }

    static final int MAX_LINE_CHARS = 20_000_000;

    private final Reader reader;
    private final PrintStream writer;
    private final MessageHandler handler;
    private boolean closed;

    StdioTransport(InputStream in, PrintStream writer, MessageHandler handler) {
        this.reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        this.writer = writer;
        this.handler = handler;
    }

    /**
     * 读循环：阻塞直至 stdin EOF、读异常或写端断开。任何出口都不向 stderr 喷栈——
     * 客户端关闭子进程是正常生命周期，不是故障。
     */
    void run() {
        StringBuilder buffer = new StringBuilder(256);
        boolean overflow = false;
        try {
            int c;
            while (!closed && (c = reader.read()) != -1) {
                if (c != '\n') {
                    if (buffer.length() < MAX_LINE_CHARS) {
                        buffer.append((char) c);
                    } else {
                        overflow = true;
                    }
                    continue;
                }
                String line = buffer.toString();
                buffer.setLength(0);
                boolean lineOverflow = overflow;
                overflow = false;
                if (lineOverflow) {
                    respond(handler.malformedResponse("line exceeds " + MAX_LINE_CHARS + " chars"));
                    continue;
                }
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                respond(handler.handle(line));
            }
            // EOF 带残行：按末消息尽力处理（客户端未换行即关流的宽容处置）
            if (!closed && !overflow && buffer.length() > 0) {
                String line = buffer.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (!line.trim().isEmpty()) {
                    respond(handler.handle(line));
                }
            }
        } catch (IOException e) {
            // 读端断管：客户端已走，静默退出
        }
    }

    private void respond(String response) {
        if (response == null) {
            return;
        }
        writer.print(response);
        writer.print('\n');
        writer.flush();
        if (writer.checkError()) {
            closed = true;
        }
    }
}
