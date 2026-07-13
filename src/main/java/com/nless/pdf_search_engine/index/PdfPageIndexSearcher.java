package com.nless.pdf_search_engine.index;

import android.graphics.RectF;

import com.nless.pdf_search_engine.core.PdfSearchQueryOptions;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchResultId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在已提取页面索引上执行纯 Java 查询。 */
public final class PdfPageIndexSearcher {

    private PdfPageIndexSearcher() {
    }

    public static List<PdfSearchResult> searchDocument(
            PdfDocumentIndex document,
            String keyword,
            PdfSearchQueryOptions options
    ) {
        List<PdfSearchResult> results = new ArrayList<>();
        if (document == null) return results;
        for (PdfPageIndex page : document.getPages()) {
            results.addAll(searchPage(
                    document.documentFingerprint,
                    page,
                    keyword,
                    options
            ));
        }
        return results;
    }

    public static List<PdfSearchResult> searchPage(
            String documentFingerprint,
            PdfPageIndex page,
            String keyword,
            PdfSearchQueryOptions options
    ) {
        List<PdfSearchResult> results = new ArrayList<>();
        if (page == null || !page.isSearchable()) return results;

        PdfSearchQueryOptions request = options != null
                ? new PdfSearchQueryOptions(options)
                : new PdfSearchQueryOptions();
        String normalizedKeyword = PdfTextNormalizer.normalizeQuery(
                keyword,
                request,
                page.source
        );
        if (normalizedKeyword.isEmpty()) return results;

        PdfNormalizedText normalizedPage = PdfTextNormalizer.normalizePage(page, request);
        if (normalizedPage.text.isEmpty()
                || normalizedPage.normalizedToToken.length != normalizedPage.text.length()) {
            return results;
        }

        int from = 0;
        int ordinal = 0;
        while (from <= normalizedPage.text.length() - normalizedKeyword.length()) {
            int found = normalizedPage.text.indexOf(normalizedKeyword, from);
            if (found < 0) break;
            int foundEnd = found + normalizedKeyword.length();

            if (!request.wholeWord || isWholeWord(normalizedPage.text, found, foundEnd)) {
                int startToken = normalizedPage.normalizedToToken[found];
                int endToken = normalizedPage.normalizedToToken[foundEnd - 1];
                if (startToken >= 0 && endToken >= startToken
                        && endToken < page.tokens.size()) {
                    PdfSearchResult result = buildResult(
                            documentFingerprint,
                            page,
                            keyword,
                            startToken,
                            endToken,
                            ordinal++,
                            request
                    );
                    if (result != null) results.add(result);
                }
            }
            from = found + Math.max(1, normalizedKeyword.length());
        }
        return results;
    }

    private static PdfSearchResult buildResult(
            String documentFingerprint,
            PdfPageIndex page,
            String keyword,
            int startToken,
            int endToken,
            int ordinal,
            PdfSearchQueryOptions options
    ) {
        List<PdfSearchRect> rects = collectRects(page, startToken, endToken);
        if (rects.isEmpty()) return null;

        PdfTextToken first = page.tokens.get(startToken);
        PdfTextToken last = page.tokens.get(endToken);
        int originalStart = clamp(first.originalStart, 0, page.originalText.length());
        int originalEnd = clamp(last.originalEnd, originalStart, page.originalText.length());
        String matched = safeSubstring(page.originalText, originalStart, originalEnd);

        int contextPadding = Math.max(0, options.contextCharacters);
        int contextStart = safeCodePointOffsetBackward(
                page.originalText,
                originalStart,
                contextPadding
        );
        int contextEnd = safeCodePointOffsetForward(
                page.originalText,
                originalEnd,
                contextPadding
        );
        String context = safeSubstring(page.originalText, contextStart, contextEnd);

        float confidence = averageConfidence(page.tokens, startToken, endToken);
        String resultId = PdfSearchResultId.create(
                documentFingerprint,
                page.pageIndex,
                page.source,
                ordinal,
                matched,
                rects
        );
        return new PdfSearchResult(
                resultId,
                keyword,
                page.pageIndex,
                page.source,
                rects,
                matched,
                originalStart,
                originalEnd - originalStart,
                context,
                originalStart - contextStart,
                originalEnd - contextStart,
                confidence
        );
    }

    private static List<PdfSearchRect> collectRects(
            PdfPageIndex page,
            int startToken,
            int endToken
    ) {
        Map<Integer, RectF> lines = new LinkedHashMap<>();
        for (int i = startToken; i <= endToken && i < page.tokens.size(); i++) {
            PdfTextToken token = page.tokens.get(i);
            if (token == null || !token.hasRect()) continue;
            RectF rect = token.rectInPdfPoint;
            RectF current = lines.get(token.lineIndex);
            if (current == null) {
                lines.put(token.lineIndex, new RectF(rect));
            } else {
                current.union(rect);
            }
        }

        List<PdfSearchRect> output = new ArrayList<>();
        for (RectF rect : lines.values()) {
            if (rect == null || rect.width() <= 0f || Math.abs(rect.height()) <= 0f) continue;
            output.add(new PdfSearchRect(
                    page.pageIndex,
                    rect,
                    page.pageWidth,
                    page.pageHeight
            ));
        }
        return output;
    }

    private static float averageConfidence(
            List<PdfTextToken> tokens,
            int start,
            int end
    ) {
        float sum = 0f;
        int count = 0;
        for (int i = start; i <= end && i < tokens.size(); i++) {
            PdfTextToken token = tokens.get(i);
            if (token == null || token.confidence < 0f) continue;
            sum += token.confidence;
            count++;
        }
        return count > 0 ? sum / count : -1f;
    }

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start <= 0 || !isWordCharacter(text.codePointBefore(start));
        boolean rightOk = end >= text.length() || !isWordCharacter(text.codePointAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static int safeCodePointOffsetBackward(String text, int index, int count) {
        int value = clamp(index, 0, text.length());
        for (int i = 0; i < count && value > 0; i++) {
            value = text.offsetByCodePoints(value, -1);
        }
        return value;
    }

    private static int safeCodePointOffsetForward(String text, int index, int count) {
        int value = clamp(index, 0, text.length());
        for (int i = 0; i < count && value < text.length(); i++) {
            value = text.offsetByCodePoints(value, 1);
        }
        return value;
    }

    private static String safeSubstring(String text, int start, int end) {
        if (text == null) return "";
        int safeStart = clamp(start, 0, text.length());
        int safeEnd = clamp(end, safeStart, text.length());
        return text.substring(safeStart, safeEnd);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
