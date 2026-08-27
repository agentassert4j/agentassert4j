package io.github.agentassert4j.cli;

import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * baseline 命令 — 为已录制的交互建立基线（幂等，可重复执行）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "baseline", description = "为已录制的交互建立基线（幂等，可重复执行）")
public class BaselineCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            int established = new BaselineService(repository).establishMissing(System.out);
            System.out.println(established > 0 ? "完成：" + established + " 个 skill 新建基线。" : "完成：所有 skill 均已有基线。");
            return 0;
        } catch (RuntimeException e) {
            System.err.println("baseline 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
