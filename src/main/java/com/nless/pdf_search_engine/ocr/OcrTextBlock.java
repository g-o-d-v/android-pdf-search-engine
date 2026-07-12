package com.nless.pdf_search_engine.ocr;

import android.graphics.RectF;

import java.util.Arrays;

public class OcrTextBlock {
    public final String text;
    public final RectF rectInBitmap;
    public final float score;

    /**
     * 文本检测框四边形，按左上、右上、右下、左下排列：
     * [x0,y0,x1,y1,x2,y2,x3,y3]。
     */
    public final float[] quadInBitmap;

    /**
     * 识别 token 在矫正后文本行中的紧致归一化框。
     * 每个 token 占 4 个 float：[left, top, right, bottom]，范围 0..1。
     * 顺序与 tokenUtf16Starts/tokenUtf16Ends 一致。
     */
    public final float[] tokenBoxesInLine;

    /** 每个识别 token 在 text 中的 UTF-16 起始下标。 */
    public final int[] tokenUtf16Starts;

    /** 每个识别 token 在 text 中的 UTF-16 结束下标（不包含）。 */
    public final int[] tokenUtf16Ends;

    public OcrTextBlock(String text, RectF rectInBitmap, float score) {
        this(text, rectInBitmap, score, null, null, null, null);
    }

    public OcrTextBlock(
            String text,
            RectF rectInBitmap,
            float score,
            float[] quadInBitmap
    ) {
        this(text, rectInBitmap, score, quadInBitmap, null, null, null);
    }

    public OcrTextBlock(
            String text,
            RectF rectInBitmap,
            float score,
            float[] quadInBitmap,
            float[] tokenBoxesInLine,
            int[] tokenUtf16Starts,
            int[] tokenUtf16Ends
    ) {
        this.text = text;
        this.rectInBitmap = rectInBitmap;
        this.score = score;
        this.quadInBitmap = quadInBitmap != null && quadInBitmap.length == 8
                ? Arrays.copyOf(quadInBitmap, quadInBitmap.length)
                : null;

        int tokenCount = tokenUtf16Starts != null ? tokenUtf16Starts.length : 0;
        boolean validTokenMetadata = tokenCount > 0
                && tokenUtf16Ends != null
                && tokenUtf16Ends.length == tokenCount
                && tokenBoxesInLine != null
                && tokenBoxesInLine.length == tokenCount * 4;

        if (validTokenMetadata) {
            this.tokenBoxesInLine = Arrays.copyOf(
                    tokenBoxesInLine,
                    tokenBoxesInLine.length
            );
            this.tokenUtf16Starts = Arrays.copyOf(
                    tokenUtf16Starts,
                    tokenUtf16Starts.length
            );
            this.tokenUtf16Ends = Arrays.copyOf(
                    tokenUtf16Ends,
                    tokenUtf16Ends.length
            );
        } else {
            this.tokenBoxesInLine = null;
            this.tokenUtf16Starts = null;
            this.tokenUtf16Ends = null;
        }
    }

    public boolean hasValidQuad() {
        return quadInBitmap != null && quadInBitmap.length == 8;
    }

    public boolean hasTokenBoxes() {
        return tokenBoxesInLine != null
                && tokenUtf16Starts != null
                && tokenUtf16Ends != null
                && tokenBoxesInLine.length == tokenUtf16Starts.length * 4
                && tokenUtf16Ends.length == tokenUtf16Starts.length;
    }

    public int getTokenCount() {
        return hasTokenBoxes() ? tokenUtf16Starts.length : 0;
    }
}
