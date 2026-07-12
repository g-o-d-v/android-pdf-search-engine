package com.nless.pdf_search_engine.index;

import android.graphics.PointF;
import android.graphics.RectF;

import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;
import com.nless.pdf_search_engine.pdfium.PdfiumTextIndexChar;
import com.nless.pdf_search_engine.pdfium.PdfiumTextIndexPage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 把 PDFium/OCR 的原始页面数据转换为统一页面索引。 */
public final class PdfPageIndexFactory {

    private PdfPageIndexFactory() {
    }

    public static PdfPageIndex fromPdfium(PdfiumTextIndexPage page) {
        if (page == null) return null;
        StringBuilder text = new StringBuilder();
        List<PdfTextToken> tokens = new ArrayList<>();
        int lineIndex = 0;
        float lastCenterY = Float.NaN;
        float lastHeight = 0f;

        for (PdfiumTextIndexChar character : page.characters) {
            if (character == null || !Character.isValidCodePoint(character.codePoint)) continue;
            String value = new String(Character.toChars(character.codePoint));
            boolean explicitBreak = character.codePoint == '\n' || character.codePoint == '\r';
            RectF rect = normalizePdfRect(character.rectInPdfPoint);

            boolean geometricBreak = false;
            if (!explicitBreak && rect != null) {
                float centerY = rect.centerY();
                float height = Math.max(0.1f, rect.height());
                geometricBreak = !Float.isNaN(lastCenterY)
                        && Math.abs(centerY - lastCenterY)
                        > Math.max(lastHeight, height) * 0.85f;
                if (geometricBreak && text.length() > 0
                        && text.charAt(text.length() - 1) != '\n'
                        && text.charAt(text.length() - 1) != '\r') {
                    int breakStart = text.length();
                    text.append('\n');
                    tokens.add(new PdfTextToken(
                            "\n",
                            breakStart,
                            text.length(),
                            null,
                            lineIndex,
                            1f
                    ));
                    lineIndex++;
                }
                lastCenterY = centerY;
                lastHeight = height;
            }

            int start = text.length();
            text.append(value);
            int end = text.length();
            tokens.add(new PdfTextToken(
                    value,
                    start,
                    end,
                    rect,
                    lineIndex,
                    1f
            ));

            if (explicitBreak) {
                lineIndex++;
                lastCenterY = Float.NaN;
                lastHeight = 0f;
            }
        }

        int status = page.status == 0
                ? PdfPageIndex.STATUS_OK
                : PdfPageIndex.STATUS_EXTRACTION_FAILED;
        return new PdfPageIndex(
                page.pageIndex,
                page.pageWidth,
                page.pageHeight,
                PdfSearchSource.PDF_TEXT_LAYER,
                text.toString(),
                tokens,
                status,
                page.rawCharCount,
                page.visibleCharCount,
                page.validBoxCount,
                status == PdfPageIndex.STATUS_OK ? 1f : 0f
        );
    }

    public static PdfPageIndex fromOcr(int pageIndex, OcrPageResult page) {
        return fromOcr(pageIndex, page, true, 0.16f);
    }

    public static PdfPageIndex fromOcr(
            int pageIndex,
            OcrPageResult page,
            boolean detectMultiColumnLayout,
            float multiColumnMinGapRatio
    ) {
        if (page == null || page.bitmapWidth <= 0 || page.bitmapHeight <= 0
                || page.pageWidth <= 0f || page.pageHeight <= 0f) {
            return null;
        }

        List<OcrTextBlock> blocks = sortOcrBlocksForReadingOrder(
                page.blocks,
                page.bitmapWidth,
                detectMultiColumnLayout,
                multiColumnMinGapRatio
        );

        StringBuilder text = new StringBuilder();
        List<PdfTextToken> tokens = new ArrayList<>();
        int lineIndex = 0;
        int visibleCharacters = 0;
        int validBoxes = 0;
        float scoreSum = 0f;
        int scoreCount = 0;

        for (OcrTextBlock block : blocks) {
            if (block == null || block.text == null || block.text.isEmpty()
                    || block.rectInBitmap == null) {
                continue;
            }
            if (text.length() > 0) {
                int start = text.length();
                text.append('\n');
                tokens.add(new PdfTextToken("\n", start, text.length(), null, lineIndex, block.score));
                lineIndex++;
            }

            if (block.hasTokenBoxes()) {
                for (int tokenIndex = 0; tokenIndex < block.getTokenCount(); tokenIndex++) {
                    int tokenStart = clamp(block.tokenUtf16Starts[tokenIndex], 0, block.text.length());
                    int tokenEnd = clamp(block.tokenUtf16Ends[tokenIndex], tokenStart, block.text.length());
                    if (tokenEnd <= tokenStart) continue;
                    String tokenText = block.text.substring(tokenStart, tokenEnd);
                    int originalStart = text.length();
                    text.append(tokenText);
                    int originalEnd = text.length();
                    RectF bitmapRect = tokenBitmapRect(block, tokenIndex);
                    RectF pdfRect = bitmapToPdfRect(bitmapRect, page);
                    tokens.add(new PdfTextToken(
                            tokenText,
                            originalStart,
                            originalEnd,
                            pdfRect,
                            lineIndex,
                            block.score
                    ));
                    visibleCharacters += countVisible(tokenText);
                    if (pdfRect != null) validBoxes += countVisible(tokenText);
                }
            } else {
                appendFallbackTokens(text, tokens, block, page, lineIndex);
                visibleCharacters += countVisible(block.text);
                validBoxes += countVisible(block.text);
            }
            scoreSum += block.score;
            scoreCount++;
        }

        return new PdfPageIndex(
                pageIndex,
                page.pageWidth,
                page.pageHeight,
                PdfSearchSource.OCR,
                text.toString(),
                tokens,
                PdfPageIndex.STATUS_OK,
                text.codePointCount(0, text.length()),
                visibleCharacters,
                validBoxes,
                scoreCount > 0 ? scoreSum / scoreCount : -1f
        );
    }

    private static List<OcrTextBlock> sortOcrBlocksForReadingOrder(
            List<OcrTextBlock> source,
            int pageWidth,
            boolean detectColumns,
            float minGapRatio
    ) {
        List<OcrTextBlock> blocks = new ArrayList<>();
        if (source != null) {
            for (OcrTextBlock block : source) {
                if (block != null && block.rectInBitmap != null) blocks.add(block);
            }
        }
        Comparator<OcrTextBlock> topLeft = Comparator
                .comparingDouble((OcrTextBlock block) -> block.rectInBitmap.top)
                .thenComparingDouble(block -> block.rectInBitmap.left);
        blocks.sort(topLeft);
        if (!detectColumns || blocks.size() < 4 || pageWidth <= 0) return blocks;

        float split = detectTwoColumnSplit(
                blocks,
                pageWidth,
                Math.max(0.08f, Math.min(0.40f, minGapRatio))
        );
        if (Float.isNaN(split)) return blocks;

        List<OcrTextBlock> ordered = new ArrayList<>(blocks.size());
        List<OcrTextBlock> band = new ArrayList<>();
        for (OcrTextBlock block : blocks) {
            RectF rect = block.rectInBitmap;
            boolean spanning = rect.left < split && rect.right > split;
            if (spanning) {
                appendColumnBand(ordered, band, split);
                ordered.add(block);
            } else {
                band.add(block);
            }
        }
        appendColumnBand(ordered, band, split);
        return ordered;
    }

    private static float detectTwoColumnSplit(
            List<OcrTextBlock> blocks,
            int pageWidth,
            float minGapRatio
    ) {
        List<RectF> candidates = new ArrayList<>();
        for (OcrTextBlock block : blocks) {
            RectF rect = block.rectInBitmap;
            if (rect != null && rect.width() < pageWidth * 0.48f) candidates.add(rect);
        }
        if (candidates.size() < 4) return Float.NaN;
        candidates.sort(Comparator.comparingDouble(RectF::centerX));

        float bestGap = 0f;
        int bestIndex = -1;
        for (int i = 0; i < candidates.size() - 1; i++) {
            float gap = candidates.get(i + 1).centerX() - candidates.get(i).centerX();
            if (gap > bestGap) {
                bestGap = gap;
                bestIndex = i;
            }
        }
        float minGap = pageWidth * minGapRatio;
        if (bestIndex < 1 || candidates.size() - bestIndex - 1 < 2 || bestGap < minGap) {
            return Float.NaN;
        }
        float split = (candidates.get(bestIndex).centerX()
                + candidates.get(bestIndex + 1).centerX()) * 0.5f;
        float leftMaxRight = 0f;
        float rightMinLeft = pageWidth;
        for (RectF rect : candidates) {
            if (rect.centerX() < split) leftMaxRight = Math.max(leftMaxRight, rect.right);
            else rightMinLeft = Math.min(rightMinLeft, rect.left);
        }
        return rightMinLeft - leftMaxRight >= minGap * 0.35f ? split : Float.NaN;
    }

    private static void appendColumnBand(
            List<OcrTextBlock> output,
            List<OcrTextBlock> band,
            float split
    ) {
        if (band.isEmpty()) return;
        band.sort((first, second) -> {
            RectF a = first.rectInBitmap;
            RectF b = second.rectInBitmap;
            int aColumn = a.centerX() < split ? 0 : 1;
            int bColumn = b.centerX() < split ? 0 : 1;
            int column = Integer.compare(aColumn, bColumn);
            if (column != 0) return column;
            int top = Float.compare(a.top, b.top);
            if (top != 0) return top;
            return Float.compare(a.left, b.left);
        });
        output.addAll(band);
        band.clear();
    }

    private static void appendFallbackTokens(
            StringBuilder text,
            List<PdfTextToken> tokens,
            OcrTextBlock block,
            OcrPageResult page,
            int lineIndex
    ) {
        int codePointCount = Math.max(1, block.text.codePointCount(0, block.text.length()));
        int codePointOrdinal = 0;
        for (int offset = 0; offset < block.text.length();) {
            int codePoint = block.text.codePointAt(offset);
            String tokenText = new String(Character.toChars(codePoint));
            int originalStart = text.length();
            text.append(tokenText);
            int originalEnd = text.length();
            float leftRatio = codePointOrdinal / (float) codePointCount;
            float rightRatio = (codePointOrdinal + 1f) / codePointCount;
            RectF bitmapRect = new RectF(
                    block.rectInBitmap.left + block.rectInBitmap.width() * leftRatio,
                    block.rectInBitmap.top,
                    block.rectInBitmap.left + block.rectInBitmap.width() * rightRatio,
                    block.rectInBitmap.bottom
            );
            tokens.add(new PdfTextToken(
                    tokenText,
                    originalStart,
                    originalEnd,
                    bitmapToPdfRect(bitmapRect, page),
                    lineIndex,
                    block.score
            ));
            offset += Character.charCount(codePoint);
            codePointOrdinal++;
        }
    }

    private static RectF tokenBitmapRect(OcrTextBlock block, int tokenIndex) {
        int offset = tokenIndex * 4;
        float left = clamp01(block.tokenBoxesInLine[offset]);
        float top = clamp01(block.tokenBoxesInLine[offset + 1]);
        float right = clamp01(block.tokenBoxesInLine[offset + 2]);
        float bottom = clamp01(block.tokenBoxesInLine[offset + 3]);
        if (right <= left || bottom <= top) return null;

        if (block.hasValidQuad()) {
            PointF p0 = mapPointInQuad(block.quadInBitmap, left, top);
            PointF p1 = mapPointInQuad(block.quadInBitmap, right, top);
            PointF p2 = mapPointInQuad(block.quadInBitmap, right, bottom);
            PointF p3 = mapPointInQuad(block.quadInBitmap, left, bottom);
            return boundingRect(p0, p1, p2, p3);
        }

        RectF line = block.rectInBitmap;
        return new RectF(
                line.left + line.width() * left,
                line.top + line.height() * top,
                line.left + line.width() * right,
                line.top + line.height() * bottom
        );
    }

    private static PointF mapPointInQuad(float[] q, float x, float y) {
        float topX = q[0] + (q[2] - q[0]) * x;
        float topY = q[1] + (q[3] - q[1]) * x;
        float bottomX = q[6] + (q[4] - q[6]) * x;
        float bottomY = q[7] + (q[5] - q[7]) * x;
        return new PointF(
                topX + (bottomX - topX) * y,
                topY + (bottomY - topY) * y
        );
    }

    private static RectF boundingRect(PointF... points) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (PointF point : points) {
            if (point == null) continue;
            left = Math.min(left, point.x);
            top = Math.min(top, point.y);
            right = Math.max(right, point.x);
            bottom = Math.max(bottom, point.y);
        }
        return right > left && bottom > top ? new RectF(left, top, right, bottom) : null;
    }

    private static RectF bitmapToPdfRect(RectF bitmapRect, OcrPageResult page) {
        if (bitmapRect == null) return null;
        float left = bitmapRect.left / page.bitmapWidth * page.pageWidth;
        float right = bitmapRect.right / page.bitmapWidth * page.pageWidth;
        float pdfBottom = page.pageHeight - bitmapRect.bottom / page.bitmapHeight * page.pageHeight;
        float pdfTop = page.pageHeight - bitmapRect.top / page.bitmapHeight * page.pageHeight;
        if (right <= left || pdfTop <= pdfBottom) return null;
        // RectF.top 保存 PDF bottom，RectF.bottom 保存 PDF top，保持 top < bottom。
        return new RectF(left, pdfBottom, right, pdfTop);
    }

    private static RectF normalizePdfRect(RectF source) {
        if (source == null) return null;
        float left = Math.min(source.left, source.right);
        float right = Math.max(source.left, source.right);
        float bottom = Math.min(source.top, source.bottom);
        float top = Math.max(source.top, source.bottom);
        if (right <= left || top <= bottom) return null;
        return new RectF(left, bottom, right, top);
    }

    private static int countVisible(String value) {
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
}
