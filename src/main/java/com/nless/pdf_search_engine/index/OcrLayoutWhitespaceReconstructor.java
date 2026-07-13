package com.nless.pdf_search_engine.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 根据 OCR token / 文本块的几何间距恢复模型遗漏的普通空格。
 *
 * <p>该类只处理同一视觉行中的普通空格；真正的换行仍由页面索引工厂生成。
 * 算法使用相对字宽和常规字距作为阈值，避免把中文正常字距或统一的标题字距
 * 误判为单词空格。</p>
 */
final class OcrLayoutWhitespaceReconstructor {

    static final int SEPARATOR_NONE = 0;
    static final int SEPARATOR_SPACE = 1;
    static final int SEPARATOR_LINE_BREAK = 2;

    private static final float MIN_NORMALIZED_SPACE_GAP = 0.004f;
    private static final float BASELINE_GAP_MULTIPLIER = 2.60f;

    private OcrLayoutWhitespaceReconstructor() {
    }

    static TokenSpacingProfile analyzeTokens(
            String text,
            float[] boxes,
            int[] utf16Starts,
            int[] utf16Ends
    ) {
        int tokenCount = utf16Starts != null ? utf16Starts.length : 0;
        boolean[] insertSpaceBefore = new boolean[Math.max(0, tokenCount)];
        if (text == null || tokenCount < 2
                || utf16Ends == null || utf16Ends.length != tokenCount
                || boxes == null || boxes.length != tokenCount * 4) {
            return new TokenSpacingProfile(insertSpaceBefore, 0f);
        }

        List<Integer> visible = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        for (int i = 0; i < tokenCount; i++) {
            String token = tokenText(text, utf16Starts, utf16Ends, i);
            if (!containsVisibleCodePoint(token) || !isValidBox(boxes, i)) continue;
            visible.add(i);
            widths.add(boxWidth(boxes, i));
        }
        if (visible.size() < 2 || widths.isEmpty()) {
            return new TokenSpacingProfile(insertSpaceBefore, median(widths));
        }

        float medianWidth = median(widths);
        int direction = readingDirection(boxes, visible);
        List<Float> ordinaryGapCandidates = new ArrayList<>();

        for (int i = 1; i < visible.size(); i++) {
            int previous = visible.get(i - 1);
            int current = visible.get(i);
            if (containsWhitespaceToken(text, utf16Starts, utf16Ends, previous + 1, current)) {
                continue;
            }
            float gap = directionalGap(boxes, previous, current, direction);
            if (gap > 0f && Float.isFinite(gap)) ordinaryGapCandidates.add(gap);
        }

        // 至少 3 个间距时才估计常规字距。只有一两个 token 的短文本，
        // 可能唯一的间距本身就是空格，不能拿它作为基线压制判断。
        float baselineGap = ordinaryGapCandidates.size() >= 3
                ? lowerQuantile(ordinaryGapCandidates, 0.35f)
                : 0f;

        for (int i = 1; i < visible.size(); i++) {
            int previous = visible.get(i - 1);
            int current = visible.get(i);
            if (containsWhitespaceToken(text, utf16Starts, utf16Ends, previous + 1, current)) {
                continue;
            }

            String previousText = tokenText(text, utf16Starts, utf16Ends, previous);
            String currentText = tokenText(text, utf16Starts, utf16Ends, current);
            float gap = directionalGap(boxes, previous, current, direction);
            if (shouldInsertSpace(
                    previousText,
                    currentText,
                    gap,
                    medianWidth,
                    baselineGap
            )) {
                insertSpaceBefore[current] = true;
            }
        }

        return new TokenSpacingProfile(insertSpaceBefore, medianWidth);
    }

    static int separatorBetweenBlocks(
            String previousText,
            float previousLeft,
            float previousTop,
            float previousRight,
            float previousBottom,
            float previousGlyphWidth,
            String currentText,
            float currentLeft,
            float currentTop,
            float currentRight,
            float currentBottom,
            float currentGlyphWidth,
            float pageWidth
    ) {
        float previousHeight = Math.max(0f, previousBottom - previousTop);
        float currentHeight = Math.max(0f, currentBottom - currentTop);
        float minHeight = Math.min(previousHeight, currentHeight);
        float maxHeight = Math.max(previousHeight, currentHeight);
        if (minHeight <= 0f || maxHeight <= 0f) return SEPARATOR_LINE_BREAK;

        float overlap = Math.max(
                0f,
                Math.min(previousBottom, currentBottom)
                        - Math.max(previousTop, currentTop)
        );
        float centerDifference = Math.abs(
                (previousTop + previousBottom) * 0.5f
                        - (currentTop + currentBottom) * 0.5f
        );
        boolean verticallyAligned = overlap >= minHeight * 0.45f
                || centerDifference <= minHeight * 0.35f;

        float horizontalGap = currentLeft - previousRight;
        boolean readingOrderIsPlausible = currentRight
                >= previousLeft - minHeight * 0.20f;
        float maxSameLineGap = Math.max(maxHeight * 3.5f, pageWidth * 0.03f);
        float pageGapCap = Math.max(maxHeight * 1.5f, pageWidth * 0.12f);
        maxSameLineGap = Math.min(maxSameLineGap, pageGapCap);

        if (!verticallyAligned
                || !readingOrderIsPlausible
                || horizontalGap > maxSameLineGap) {
            return SEPARATOR_LINE_BREAK;
        }

        if (horizontalGap <= 0f
                || endsWithWhitespace(previousText)
                || startsWithWhitespace(currentText)
                || suppressSpaceAtBoundary(previousText, currentText)) {
            return SEPARATOR_NONE;
        }

        float typicalGlyphWidth = medianPositive(previousGlyphWidth, currentGlyphWidth);
        if (typicalGlyphWidth <= 0f) typicalGlyphWidth = minHeight * 0.55f;
        float factor = spacingFactor(previousText, currentText);
        float threshold = Math.max(
                1.5f,
                Math.max(typicalGlyphWidth * factor, minHeight * 0.10f)
        );
        return horizontalGap >= threshold ? SEPARATOR_SPACE : SEPARATOR_NONE;
    }

    static float estimateGlyphWidthPixels(
            String text,
            float[] boxes,
            int[] utf16Starts,
            int[] utf16Ends,
            float blockWidth
    ) {
        TokenSpacingProfile profile = analyzeTokens(text, boxes, utf16Starts, utf16Ends);
        if (profile.medianTokenWidth > 0f && blockWidth > 0f) {
            return profile.medianTokenWidth * blockWidth;
        }
        int visibleCount = countVisibleCodePoints(text);
        return visibleCount > 0 && blockWidth > 0f
                ? blockWidth / visibleCount
                : 0f;
    }

    private static boolean shouldInsertSpace(
            String previousText,
            String currentText,
            float gap,
            float medianWidth,
            float baselineGap
    ) {
        if (!(gap > 0f) || !Float.isFinite(gap) || medianWidth <= 0f) return false;
        if (suppressSpaceAtBoundary(previousText, currentText)) return false;

        float threshold = Math.max(
                MIN_NORMALIZED_SPACE_GAP,
                medianWidth * spacingFactor(previousText, currentText)
        );
        if (baselineGap > 0f) {
            threshold = Math.max(threshold, baselineGap * BASELINE_GAP_MULTIPLIER);
        }
        return gap >= threshold;
    }

    private static float spacingFactor(String previousText, String currentText) {
        int previous = lastVisibleCodePoint(previousText);
        int current = firstVisibleCodePoint(currentText);
        if (previous < 0 || current < 0) return 0.45f;

        boolean previousLatinOrDigit = isLatinOrDigit(previous);
        boolean currentLatinOrDigit = isLatinOrDigit(current);
        boolean previousCjk = isCjk(previous);
        boolean currentCjk = isCjk(current);

        if ((previousLatinOrDigit && currentCjk)
                || (previousCjk && currentLatinOrDigit)) {
            return 0.30f;
        }
        if (previousLatinOrDigit && currentLatinOrDigit) return 0.42f;
        if (previousCjk && currentCjk) return 0.52f;
        return 0.45f;
    }

    private static boolean suppressSpaceAtBoundary(String previousText, String currentText) {
        int previous = lastVisibleCodePoint(previousText);
        int current = firstVisibleCodePoint(currentText);
        if (previous < 0 || current < 0) return true;
        return isOpeningPunctuation(previous)
                || isClosingPunctuation(current)
                || isTightConnector(previous)
                || isTightConnector(current);
    }

    private static boolean isOpeningPunctuation(int codePoint) {
        return "([{<（［｛《〈「『【〔〖〘〚“‘".indexOf(codePoint) >= 0;
    }

    private static boolean isClosingPunctuation(int codePoint) {
        return ")]}>）］｝》〉」』】〕〗〙〛，。！？；：、,.!?;:%％".indexOf(codePoint) >= 0;
    }

    private static boolean isTightConnector(int codePoint) {
        return "-‐‑‒–—_/\\'’·•".indexOf(codePoint) >= 0;
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int readingDirection(float[] boxes, List<Integer> visible) {
        if (visible.size() < 2) return 1;
        float firstCenter = boxCenterX(boxes, visible.get(0));
        float lastCenter = boxCenterX(boxes, visible.get(visible.size() - 1));
        return lastCenter + 0.0001f >= firstCenter ? 1 : -1;
    }

    private static float directionalGap(
            float[] boxes,
            int previous,
            int current,
            int direction
    ) {
        if (!isValidBox(boxes, previous) || !isValidBox(boxes, current)) {
            return -1f;
        }
        int previousOffset = previous * 4;
        int currentOffset = current * 4;
        return direction >= 0
                ? boxes[currentOffset] - boxes[previousOffset + 2]
                : boxes[previousOffset] - boxes[currentOffset + 2];
    }

    private static boolean containsWhitespaceToken(
            String text,
            int[] starts,
            int[] ends,
            int fromInclusive,
            int toExclusive
    ) {
        for (int i = Math.max(0, fromInclusive); i < Math.min(toExclusive, starts.length); i++) {
            String token = tokenText(text, starts, ends, i);
            if (containsWhitespace(token)) return true;
        }
        return false;
    }

    private static String tokenText(String text, int[] starts, int[] ends, int index) {
        if (text == null || starts == null || ends == null
                || index < 0 || index >= starts.length || index >= ends.length) {
            return "";
        }
        int start = clamp(starts[index], 0, text.length());
        int end = clamp(ends[index], start, text.length());
        return text.substring(start, end);
    }

    private static boolean isValidBox(float[] boxes, int index) {
        int offset = index * 4;
        if (boxes == null || offset < 0 || offset + 3 >= boxes.length) return false;
        float left = boxes[offset];
        float top = boxes[offset + 1];
        float right = boxes[offset + 2];
        float bottom = boxes[offset + 3];
        return Float.isFinite(left) && Float.isFinite(top)
                && Float.isFinite(right) && Float.isFinite(bottom)
                && right > left && bottom > top;
    }

    private static float boxWidth(float[] boxes, int index) {
        int offset = index * 4;
        return boxes[offset + 2] - boxes[offset];
    }

    private static float boxCenterX(float[] boxes, int index) {
        int offset = index * 4;
        return (boxes[offset] + boxes[offset + 2]) * 0.5f;
    }

    private static float median(List<Float> values) {
        if (values == null || values.isEmpty()) return 0f;
        List<Float> sorted = new ArrayList<>();
        for (Float value : values) {
            if (value != null && Float.isFinite(value) && value > 0f) sorted.add(value);
        }
        if (sorted.isEmpty()) return 0f;
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) * 0.5f;
    }

    private static float lowerQuantile(List<Float> values, float quantile) {
        if (values == null || values.isEmpty()) return 0f;
        List<Float> sorted = new ArrayList<>();
        for (Float value : values) {
            if (value != null && Float.isFinite(value) && value > 0f) sorted.add(value);
        }
        if (sorted.isEmpty()) return 0f;
        Collections.sort(sorted);
        int index = Math.round((sorted.size() - 1) * clamp01(quantile));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static float medianPositive(float first, float second) {
        boolean firstValid = Float.isFinite(first) && first > 0f;
        boolean secondValid = Float.isFinite(second) && second > 0f;
        if (firstValid && secondValid) return (first + second) * 0.5f;
        if (firstValid) return first;
        return secondValid ? second : 0f;
    }

    private static boolean containsVisibleCodePoint(String value) {
        return firstVisibleCodePoint(value) >= 0;
    }

    private static boolean containsWhitespace(String value) {
        if (value == null) return false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean startsWithWhitespace(String value) {
        if (value == null || value.isEmpty()) return false;
        return Character.isWhitespace(value.codePointAt(0));
    }

    private static boolean endsWithWhitespace(String value) {
        if (value == null || value.isEmpty()) return false;
        return Character.isWhitespace(value.codePointBefore(value.length()));
    }

    private static int firstVisibleCodePoint(String value) {
        if (value == null) return -1;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) {
                return codePoint;
            }
            offset += Character.charCount(codePoint);
        }
        return -1;
    }

    private static int lastVisibleCodePoint(String value) {
        if (value == null) return -1;
        for (int offset = value.length(); offset > 0;) {
            int codePoint = value.codePointBefore(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) {
                return codePoint;
            }
            offset -= Character.charCount(codePoint);
        }
        return -1;
    }

    private static int countVisibleCodePoints(String value) {
        int count = 0;
        if (value == null) return count;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) count++;
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class TokenSpacingProfile {
        private final boolean[] insertSpaceBefore;
        final float medianTokenWidth;

        TokenSpacingProfile(boolean[] insertSpaceBefore, float medianTokenWidth) {
            this.insertSpaceBefore = insertSpaceBefore != null
                    ? insertSpaceBefore
                    : new boolean[0];
            this.medianTokenWidth = Math.max(0f, medianTokenWidth);
        }

        boolean shouldInsertSpaceBefore(int tokenIndex) {
            return tokenIndex >= 0
                    && tokenIndex < insertSpaceBefore.length
                    && insertSpaceBefore[tokenIndex];
        }
    }
}
