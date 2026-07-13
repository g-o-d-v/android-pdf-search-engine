package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchQueryOptions;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 文本规范化，并保留规范化字符到原始 token 的映射。 */
final class PdfTextNormalizer {

    private static final char LINE_BOUNDARY = '\u0000';

    private PdfTextNormalizer() {
    }

    static PdfNormalizedText normalizePage(
            PdfPageIndex page,
            PdfSearchQueryOptions options
    ) {
        PdfSearchQueryOptions request = options != null
                ? options
                : new PdfSearchQueryOptions();
        StringBuilder output = new StringBuilder();
        List<Integer> mapping = new ArrayList<>();

        List<PdfTextToken> tokens = page != null ? page.tokens : null;
        if (tokens == null) return new PdfNormalizedText("", new int[0]);
        PdfSearchSource source = page != null ? page.source : null;
        boolean skipWhitespaceAfterLineBreak = false;

        for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
            PdfTextToken token = tokens.get(tokenIndex);
            if (token == null) continue;

            String raw = token.text;
            if (raw == null || raw.isEmpty()) continue;

            if (token.isLineBreak()) {
                boolean joinedHyphen = false;
                if (request.joinHyphenatedLineBreaks) {
                    trimTrailingWhitespace(output, mapping);
                    if (output.length() > 0
                            && output.charAt(output.length() - 1) == '-') {
                        output.deleteCharAt(output.length() - 1);
                        if (!mapping.isEmpty()) mapping.remove(mapping.size() - 1);
                        joinedHyphen = true;
                    }
                }
                if (joinedHyphen || request.allowCrossLineMatch) {
                    // 行末/行首空格通常只是版面换行残留，应和换行一起移除。
                    trimTrailingWhitespace(output, mapping);
                    skipWhitespaceAfterLineBreak = true;
                } else {
                    output.append(LINE_BOUNDARY);
                    mapping.add(tokenIndex);
                    skipWhitespaceAfterLineBreak = false;
                }
                continue;
            }

            if (token.isWhitespace()) {
                if (skipWhitespaceAfterLineBreak) continue;
                if (!request.ignoreWhitespaceForMatching) {
                    appendWhitespace(output, mapping, tokenIndex, request);
                }
                continue;
            }

            skipWhitespaceAfterLineBreak = false;
            appendFragment(output, mapping, raw, tokenIndex, request, source);
        }

        int[] indexes = new int[mapping.size()];
        for (int i = 0; i < mapping.size(); i++) indexes[i] = mapping.get(i);
        return new PdfNormalizedText(output.toString(), indexes);
    }

    static String normalizeQuery(String query, PdfSearchQueryOptions options) {
        return normalizeQuery(query, options, null);
    }

    static String normalizeQuery(
            String query,
            PdfSearchQueryOptions options,
            PdfSearchSource source
    ) {
        if (query == null) return "";
        PdfSearchQueryOptions request = options != null
                ? options
                : new PdfSearchQueryOptions();

        String value = request.normalizeUnicode
                ? Normalizer.normalize(query, Normalizer.Form.NFKC)
                : query;
        if (!request.caseSensitive) value = value.toLowerCase(Locale.ROOT);

        StringBuilder output = new StringBuilder();
        boolean previousWhitespace = false;
        boolean skipWhitespaceAfterLineBreak = false;

        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isLineBreakCodePoint(codePoint)) {
                // CRLF 作为一个逻辑换行处理。
                if (codePoint == '\r' && offset < value.length()
                        && value.codePointAt(offset) == '\n') {
                    offset += Character.charCount('\n');
                }
                boolean joinedHyphen = false;
                if (request.joinHyphenatedLineBreaks) {
                    trimTrailingWhitespace(output);
                    if (output.length() > 0
                            && output.charAt(output.length() - 1) == '-') {
                        output.deleteCharAt(output.length() - 1);
                        joinedHyphen = true;
                    }
                }
                if (joinedHyphen || request.allowCrossLineMatch) {
                    trimTrailingWhitespace(output);
                    skipWhitespaceAfterLineBreak = true;
                    previousWhitespace = false;
                } else {
                    output.append(LINE_BOUNDARY);
                    skipWhitespaceAfterLineBreak = false;
                    previousWhitespace = false;
                }
                continue;
            }

            if (Character.isWhitespace(codePoint)) {
                if (request.ignoreWhitespaceForMatching || skipWhitespaceAfterLineBreak) continue;
                if (request.collapseWhitespace) {
                    if (!previousWhitespace) output.append(' ');
                } else {
                    output.appendCodePoint(codePoint);
                }
                previousWhitespace = true;
                continue;
            }

            skipWhitespaceAfterLineBreak = false;
            codePoint = canonicalizeOZero(codePoint, request, source);
            output.appendCodePoint(codePoint);
            previousWhitespace = false;
        }
        return output.toString();
    }

    private static void appendWhitespace(
            StringBuilder output,
            List<Integer> mapping,
            int tokenIndex,
            PdfSearchQueryOptions options
    ) {
        if (options.collapseWhitespace
                && output.length() > 0
                && Character.isWhitespace(output.charAt(output.length() - 1))) {
            return;
        }
        output.append(' ');
        mapping.add(tokenIndex);
    }

    private static void appendFragment(
            StringBuilder output,
            List<Integer> mapping,
            String fragment,
            int tokenIndex,
            PdfSearchQueryOptions options,
            PdfSearchSource source
    ) {
        String normalized = options.normalizeUnicode
                ? Normalizer.normalize(fragment, Normalizer.Form.NFKC)
                : fragment;
        if (!options.caseSensitive) normalized = normalized.toLowerCase(Locale.ROOT);

        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isLineBreakCodePoint(codePoint)) {
                // 页面工厂通常会生成独立换行 token；这里兼容包含内嵌换行的异常 token。
                if (options.allowCrossLineMatch) {
                    trimTrailingWhitespace(output, mapping);
                } else {
                    output.append(LINE_BOUNDARY);
                    mapping.add(tokenIndex);
                }
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                if (!options.ignoreWhitespaceForMatching) {
                    appendWhitespace(output, mapping, tokenIndex, options);
                }
                continue;
            }
            codePoint = canonicalizeOZero(codePoint, options, source);
            int before = output.length();
            output.appendCodePoint(codePoint);
            for (int i = before; i < output.length(); i++) mapping.add(tokenIndex);
        }
    }

    private static int canonicalizeOZero(
            int codePoint,
            PdfSearchQueryOptions options,
            PdfSearchSource source
    ) {
        if (source == PdfSearchSource.OCR
                && options.tolerateOcrOZeroConfusion
                && (codePoint == '0' || codePoint == 'O' || codePoint == 'o')) {
            return '0';
        }
        return codePoint;
    }

    private static boolean isLineBreakCodePoint(int codePoint) {
        return codePoint == '\n'
                || codePoint == '\r'
                || codePoint == 0x0085
                || codePoint == 0x2028
                || codePoint == 0x2029;
    }

    private static void trimTrailingWhitespace(
            StringBuilder output,
            List<Integer> mapping
    ) {
        while (output.length() > 0
                && Character.isWhitespace(output.charAt(output.length() - 1))) {
            output.deleteCharAt(output.length() - 1);
            if (!mapping.isEmpty()) mapping.remove(mapping.size() - 1);
        }
    }

    private static void trimTrailingWhitespace(StringBuilder output) {
        while (output.length() > 0
                && Character.isWhitespace(output.charAt(output.length() - 1))) {
            output.deleteCharAt(output.length() - 1);
        }
    }
}
