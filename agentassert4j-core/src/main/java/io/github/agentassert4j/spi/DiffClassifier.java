package io.github.agentassert4j.spi;

import io.github.agentassert4j.result.DiffClassification;

/**
 * Prompt Diff 分类器 SPI — 可选实现。
 *
 * <p>注意：v3.1 后 DiffClassification 仅作为报告注释，
 * 不再用于决定测试范围（改为数据驱动）。</p>
 */
public interface DiffClassifier {

    DiffClassification classify(String oldPrompt, String newPrompt);
    String name();
}
