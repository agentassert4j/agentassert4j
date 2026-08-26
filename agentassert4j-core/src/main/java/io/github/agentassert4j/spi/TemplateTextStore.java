package io.github.agentassert4j.spi;

/**
 * 模板文本库域 SPI — 按 hash 去重存储模板原文。
 *
 * <p>hash 不可逆：模板原文只此一份，删除即永久丢失。
 * v1 角色从"prompt 文本缓存"演进为模板文本库（template_hash → 模板原文）。</p>
 */
public interface TemplateTextStore {

    void saveTemplateText(String hash, String templateText);

    String findTemplateText(String hash);
}
