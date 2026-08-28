package io.github.agentassert4j.util;

import java.util.*;

/**
 * 文本差异工具 — 渲染两段响应文本的人类可读差异摘要（重放证据注记用）。
 * 零外部依赖，纯 java.base 实现。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class TextDiffUtils {

    private TextDiffUtils() {
    }

    /**
     * 对比两个字符串，返回人类可读的差异摘要。
     * 如果完全相同返回 null，否则返回差异描述。
     */
    public static String diff(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";
        if (oldText.equals(newText)) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("文本发生变化");

        // 按行分割
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        int oldLen = oldLines.length;
        int newLen = newLines.length;
        int maxLines = Math.max(oldLen, newLen);

        int addedLines = 0;
        int removedLines = 0;
        List<String> changes = new ArrayList<>();

        // 简单的逐行对比
        Set<String> newLineSet = new HashSet<>();
        for (String line : newLines) {
            newLineSet.add(line.trim());
        }
        Set<String> oldLineSet = new HashSet<>();
        for (String line : oldLines) {
            oldLineSet.add(line.trim());
        }

        for (int i = 0; i < maxLines; i++) {
            String oldLine = i < oldLen ? oldLines[i] : null;
            String newLine = i < newLen ? newLines[i] : null;

            if (oldLine == null) {
                addedLines++;
                if (changes.size() < 5) {
                    changes.add("+ [行" + (i + 1) + "] " + truncate(newLine, 80));
                }
            } else if (newLine == null) {
                removedLines++;
                if (changes.size() < 5) {
                    changes.add("- [行" + (i + 1) + "] " + truncate(oldLine, 80));
                }
            } else if (!oldLine.equals(newLine)) {
                if (changes.size() < 5) {
                    changes.add("~ [行" + (i + 1) + "] " + truncate(newLine, 80));
                }
            }
        }

        // 统计新增/删除的独立行（不在另一边出现的行）
        Set<String> reallyAdded = new LinkedHashSet<>(newLineSet);
        reallyAdded.removeAll(oldLineSet);
        Set<String> reallyRemoved = new LinkedHashSet<>(oldLineSet);
        reallyRemoved.removeAll(newLineSet);

        sb.append(": 旧 ").append(oldLen).append(" 行 → 新 ").append(newLen).append(" 行");
        if (!reallyAdded.isEmpty()) {
            sb.append(", 新增 ").append(reallyAdded.size()).append(" 行");
        }
        if (!reallyRemoved.isEmpty()) {
            sb.append(", 删除 ").append(reallyRemoved.size()).append(" 行");
        }

        if (!changes.isEmpty()) {
            sb.append('\n');
            for (String change : changes) {
                sb.append(change).append('\n');
            }
            int remaining = Math.abs(newLen - oldLen) - changes.size();
            if (remaining > 0) {
                sb.append("... 还有 ").append(remaining).append(" 处变更");
            }
        }

        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
