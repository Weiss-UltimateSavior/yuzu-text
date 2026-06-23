package com.yuzugame.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeUtils 单元测试 —— 验证兑换码规范化和 JSON 转义。
 */
class CodeUtilsTest {

    @Test
    void normalizeCode_shouldTrimAndUppercase() {
        assertEquals("ABC123", CodeUtils.normalizeCode("  abc123  "));
    }

    @Test
    void normalizeCode_shouldHandleNull() {
        assertNull(CodeUtils.normalizeCode(null));
    }

    @Test
    void normalizeCode_shouldHandleEmpty() {
        assertEquals("", CodeUtils.normalizeCode("   "));
    }

    @Test
    void jsonEscape_shouldEscapeQuotes() {
        String result = CodeUtils.jsonEscape("hello \"world\"");
        assertEquals("\"hello \\\"world\\\"\"", result);
    }

    @Test
    void jsonEscape_shouldEscapeBackslash() {
        String result = CodeUtils.jsonEscape("path\\to\\file");
        assertEquals("\"path\\\\to\\\\file\"", result);
    }

    @Test
    void jsonEscape_shouldEscapeNewlines() {
        String result = CodeUtils.jsonEscape("line1\nline2");
        assertEquals("\"line1\\nline2\"", result);
    }

    @Test
    void jsonEscape_shouldEscapeControlChars() {
        String result = CodeUtils.jsonEscape("tab\there");
        assertEquals("\"tab\\there\"", result);
    }

    @Test
    void jsonEscape_shouldEscapeUnicodeControlBelow0x20() {
        String result = CodeUtils.jsonEscape("a\u0001b");
        assertEquals("\"a\\u0001b\"", result);
    }

    @Test
    void jsonEscape_shouldHandleNull() {
        assertEquals("\"\"", CodeUtils.jsonEscape(null));
    }

    @Test
    void jsonEscape_shouldPreserveNormalText() {
        String result = CodeUtils.jsonEscape("hello world");
        assertEquals("\"hello world\"", result);
    }

    @Test
    void jsonEscape_shouldPreserveChineseCharacters() {
        String result = CodeUtils.jsonEscape("你好世界");
        assertEquals("\"你好世界\"", result);
    }
}
