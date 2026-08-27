package io.github.agentassert4j.spi;

/**
 * 存储后端操作失败 — SQL/IO 层错误向上层可见的信号。
 *
 * <p>静默吞掉存储失败会把"磁盘满/库锁死"伪装成"成功"或"无数据"，属于禁止行为。
 * 调用方按所在层选择退化策略：录制管道捕获并计入丢弃/失败计数，不阻塞业务，
 * CLI 与基线管理链路向上透传给用户裁决。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public class StorageException extends RuntimeException {

    public StorageException(String operation, Throwable cause) {
        super("Storage operation failed: " + operation, cause);
    }
}
