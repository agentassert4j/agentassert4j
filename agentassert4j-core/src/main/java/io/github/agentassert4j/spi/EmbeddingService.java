package io.github.agentassert4j.spi;

/**
 * 嵌入服务 SPI — 可选增强插件。
 *
 * <p>核心链路不依赖此接口。所有使用 Embedding 的代码路径通过
 * {@code if (embeddingService.isAvailable())} 守卫。</p>
 */
public interface EmbeddingService {

    float[] embed(String text);
    boolean isAvailable();
}
