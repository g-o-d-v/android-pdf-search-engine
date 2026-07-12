package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchQueryOptions;

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

        for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
            PdfTextToken token = tokens.get(tokenIndex);
            if (token == null) continue;

            String raw = token.text;
            if (raw == null || raw.isEmpty()) continue;

            if (token.isLineBreak()) {
                if (request.joinHyphenatedLineBreaks
                        && output.length() > 0
                        && output.charAt(output.length() - 1) == '-') {
                    output.deleteCharAt(output.length() - 1);
                    if (!mapping.isEmpty()) mapping.remove(mapping.size() - 1);
                    continue;
                }
                if (!request.allowCrossLineMatch) {
                    output.append(LINE_BOUNDARY);
                    mapping.add(tokenIndex);
                } else if (!request.ignoreWhitespaceForMatching) {
                    appendWhitespace(output, mapping, tokenIndex, request);
                }
                continue;
            }

            if (token.isWhitespace()) {
                if (!request.ignoreWhitespaceForMatching) {
                    appendWhitespace(output, mapping, tokenIndex, request);
                }
                continue;
            }

            appendFragment(output, mapping, raw, tokenIndex, request);
        }

        int[] indexes = new int[mapping.size()];
        for (int i = 0; i < mapping.size(); i++) indexes[i] = mapping.get(i);
        return new PdfNormalizedText(output.toString(), indexes);
    }

    static String normalizeQuery(String query, PdfSearchQueryOptions options) {
        if (query == null) return "";
        PdfSearchQueryOptions request = options != null
                ? options
                : new PdfSearchQueryOptions();

        String source = query;
        if (request.normalizeUnicode) {
            source = Normalizer.normalize(source, Normalizer.Form.NFKC);
        }
        if (!request.caseSensitive) source = source.toLowerCase(Locale.ROOT);

        StringBuilder output = new StringBuilder();
        boolean previousWhitespace = false;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (request.ignoreWhitespaceForMatching) continue;
                if (request.collapseWhitespace) {
                    if (!previousWhitespace) output.append(' ');
                } else {
                    output.appendCodePoint(codePoint);
                }
                previousWhitespace = true;
            } else {
                output.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
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
            PdfSearchQueryOptions options
    ) {
        String normalized = options.normalizeUnicode
                ? Normalizer.normalize(fragment, Normalizer.Form.NFKC)
                : fragment;
        if (!options.caseSensitive) normalized = normalized.toLowerCase(Locale.ROOT);

        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (!options.ignoreWhitespaceForMatching) {
                    appendWhitespace(output, mapping, tokenIndex, options);
                }
                continue;
            }
            int before = output.length();
            output.appendCodePoint(codePoint);
            for (int i = before; i < output.length(); i++) mapping.add(tokenIndex);
        }
    }
}
