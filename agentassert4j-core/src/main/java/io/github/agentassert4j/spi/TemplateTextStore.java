package io.github.agentassert4j.spi;

/**
 * 模板文本库域 SPI — 按 hash 读取模板原文。
 *
 * <p>hash 不可逆：模板原文只此一份，删除即永久丢失。
 * 写入由存储实现内部承接（随交互记录落库同源），SPI 只暴露读取面。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface TemplateTextStore {

    String findTemplateText(String hash);
}
