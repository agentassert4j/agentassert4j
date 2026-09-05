package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CliSupport 键展示形态的单元测试——人读短形与完整键的边界（完整键只在 JSON 证据）。
 *
 * @author axy-yxa
 * @since 2026-09-02
 */
class CliSupportKeyDisplayTest {

    @Test
    @DisplayName("声明锚点：标签@细分短形")
    void declaredAnchor_labelAtSubdivision() {
        assertEquals("audit@89617cae", CliSupport.displayKey("invocation:audit:89617cae34e087c4be8ff2fd51ba16e6b93905bfb55e7bfb3522890c58f7b213"));
    }

    @Test
    @DisplayName("无细分声明键只显示标签")
    void declaredAnchor_noSubdivision() {
        assertEquals("audit", CliSupport.displayKey("invocation:audit"));
    }

    @Test
    @DisplayName("骨架/模板锚点：短名@细分短形")
    void skeletonAndTemplate_shortNames() {
        assertEquals("skl@89617cae", CliSupport.displayKey("skeleton:89617cae"));
        assertEquals("tpl@89617cae", CliSupport.displayKey("template:89617cae34e0"));
    }

    @Test
    @DisplayName("请求锚点：有锚显示短形，no-anchor 只显示 adhoc；未知形态原样")
    void adhocAndUnknown() {
        assertEquals("adhoc@a1b2c3d4", CliSupport.displayKey("adhoc:a1b2c3d4e5"));
        assertEquals("adhoc", CliSupport.displayKey("adhoc:no-anchor"));
        assertEquals("weird-key", CliSupport.displayKey("weird-key"));
        assertEquals("(unresolved invocation)", CliSupport.displayKey(null));
        assertEquals("(unresolved invocation)", CliSupport.displayKey(""));
    }

    @Test
    @DisplayName("中文标签原样可读（编码器不转义非 ASCII）")
    void chineseLabelReadable() {
        assertEquals("代码审查@abcd1234", CliSupport.displayKey("invocation:代码审查:abcd1234e5"));
    }

    @Test
    @DisplayName("含文法字符的标签以解码形展示（键存编码形，人读词汇表原样）")
    void encodedLabelDecoded() {
        assertEquals("plan:v2@abcd1234", CliSupport.displayKey("invocation:plan%3Av2:abcd1234e5"));
    }
}
