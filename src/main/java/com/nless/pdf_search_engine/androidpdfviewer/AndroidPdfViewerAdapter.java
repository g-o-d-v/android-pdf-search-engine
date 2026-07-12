package com.nless.pdf_search_engine.androidpdfviewer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.github.barteksc.pdfviewer.PDFView;
import com.nless.pdf_search_engine.coordinate.PdfCoordinateConverter;
import com.nless.pdf_search_engine.core.OcrDebugGeometryType;
import com.nless.pdf_search_engine.core.OcrDebugRect;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将搜索库的 PDF point 坐标适配到 AndroidPdfViewer。
 *
 * <p>高亮内部保存页面归一化坐标，绘制时再使用 OnDrawListener 当前页宽高进行缩放，
 * 因此缩放、双击放大和不同设备尺寸不会让高亮框与文字分离。</p>
 */
public class AndroidPdfViewerAdapter {

    private final PDFView pdfView;

    private int cachedPageCount = -1;
    private float cachedMaxPageWidth = -1f;
    private float cachedMaxPageHeight = -1f;

    public AndroidPdfViewerAdapter(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    public List<ViewerSearchHighlight> convertResults(List<PdfSearchResult> results) {
        List<ViewerSearchHighlight> highlights = new ArrayList<>();

        if (pdfView == null || results == null || results.isEmpty()) {
            return highlights;
        }

        for (PdfSearchResult result : results) {
            if (result == null || result.rects == null) continue;

            for (PdfSearchRect rect : result.rects) {
                ViewerSearchHighlight highlight = convertSingle(
                        rect,
                        result.resultId,
                        result.source,
                        result.keyword,
                        result.matchedText
                );
                if (highlight != null) {
                    highlights.add(highlight);
                }
            }
        }

        sortHighlights(highlights);
        return highlights;
    }

    public ViewerSearchHighlight convertSingle(
            PdfSearchRect rect,
            PdfSearchSource source,
            String keyword,
            String matchedText
    ) {
        return convertSingle(rect, null, source, keyword, matchedText);
    }

    public ViewerSearchHighlight convertSingle(
            PdfSearchRect rect,
            String resultId,
            PdfSearchSource source,
            String keyword,
            String matchedText
    ) {
        if (rect == null) return null;

        RectF ratioRect = PdfCoordinateConverter.searchRectToPageRatio(rect);
        if (ratioRect == null) return null;

        RectF legacyPageRect = null;
        try {
            float pageWidth = pdfView.getPageSize(rect.pageIndex).getWidth();
            float pageHeight = pdfView.getPageSize(rect.pageIndex).getHeight();
            legacyPageRect = PdfCoordinateConverter.pageRatioToPageRect(
                    ratioRect,
                    pageWidth,
                    pageHeight
            );
        } catch (Exception ignored) {
            // PDFView 尚未完成加载时，归一化坐标仍然有效；绘制阶段可正常转换。
        }

        return new ViewerSearchHighlight(
                resultId,
                rect.pageIndex,
                ratioRect,
                legacyPageRect,
                source,
                keyword,
                matchedText
        );
    }

    /**
     * 将高亮转换为 OnDrawListener 当前回调中的页面局部坐标。
     */
    public RectF toPageCanvasRect(
            ViewerSearchHighlight highlight,
            float pageWidth,
            float pageHeight
    ) {
        if (highlight == null) return null;
        return PdfCoordinateConverter.pageRatioToPageRect(
                highlight.rectInPageRatio,
                pageWidth,
                pageHeight
        );
    }

    /**
     * 在 AndroidPdfViewer 的 OnDrawListener / OnDrawAllListener 中绘制指定页高亮。
     *
     * @param swipeVertical true 表示纵向翻页；false 表示横向翻页。
     */
    public void drawHighlights(
            Canvas canvas,
            float pageWidth,
            float pageHeight,
            int displayedPage,
            List<ViewerSearchHighlight> highlights,
            Paint paint,
            boolean swipeVertical
    ) {
        if (canvas == null || paint == null || highlights == null || highlights.isEmpty()) {
            return;
        }

        int firstIndex = findFirstHighlightIndex(highlights, displayedPage);
        if (firstIndex < 0) return;

        float crossAxisOffset = getCrossAxisPageCenterOffset(
                displayedPage,
                pageWidth,
                pageHeight,
                swipeVertical
        );

        for (int i = firstIndex; i < highlights.size(); i++) {
            ViewerSearchHighlight highlight = highlights.get(i);
            if (highlight == null) continue;
            if (highlight.pageIndex != displayedPage) break;

            RectF drawRect = toPageCanvasRect(highlight, pageWidth, pageHeight);
            if (drawRect == null) continue;

            if (swipeVertical) {
                drawRect.offset(crossAxisOffset, 0f);
            } else {
                drawRect.offset(0f, crossAxisOffset);
            }

            canvas.drawRect(drawRect, paint);
        }
    }

    /**
     * 绘制 OCR 几何调试覆盖层。
     *
     * <p>检测行框和 token 框使用不同画笔，便于判断误差来自文本检测、
     * CTC 对齐还是最终关键词合并。正式版本应关闭此覆盖层。</p>
     */
    public void drawOcrDebugGeometry(
            Canvas canvas,
            float pageWidth,
            float pageHeight,
            int displayedPage,
            List<OcrDebugRect> debugRects,
            Paint detectionPaint,
            Paint tokenPaint,
            boolean swipeVertical
    ) {
        if (canvas == null || debugRects == null || debugRects.isEmpty()) {
            return;
        }

        float crossAxisOffset = getCrossAxisPageCenterOffset(
                displayedPage,
                pageWidth,
                pageHeight,
                swipeVertical
        );

        for (OcrDebugRect item : debugRects) {
            if (item == null || item.pageIndex != displayedPage
                    || item.rectInPageRatio == null) {
                continue;
            }

            Paint paint = item.type == OcrDebugGeometryType.DETECTION_BLOCK
                    ? detectionPaint
                    : tokenPaint;
            if (paint == null) {
                continue;
            }

            RectF drawRect = PdfCoordinateConverter.pageRatioToPageRect(
                    item.rectInPageRatio,
                    pageWidth,
                    pageHeight
            );
            if (drawRect == null) {
                continue;
            }

            if (swipeVertical) {
                drawRect.offset(crossAxisOffset, 0f);
            } else {
                drawRect.offset(0f, crossAxisOffset);
            }
            canvas.drawRect(drawRect, paint);
        }
    }

    /**
     * 清除页面尺寸缓存。PDFView 加载新文档后应调用一次。
     */
    public void invalidatePageSizeCache() {
        cachedPageCount = -1;
        cachedMaxPageWidth = -1f;
        cachedMaxPageHeight = -1f;
    }

    /**
     * convertResults() 已按页码排序，因此可二分定位当前页的第一条结果，
     * 避免全文搜索命中很多时每个绘制帧都扫描全部高亮。
     */
    private int findFirstHighlightIndex(
            List<ViewerSearchHighlight> highlights,
            int displayedPage
    ) {
        int low = 0;
        int high = highlights.size() - 1;
        int found = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            ViewerSearchHighlight item = highlights.get(mid);
            if (item == null || item.pageIndex >= displayedPage) {
                if (item != null && item.pageIndex == displayedPage) {
                    found = mid;
                }
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found;
    }

    /**
     * AndroidPdfViewer 绘制页面位图时会在非滚动轴方向居中页面，但其 onDraw 回调
     * 只平移了页面在滚动轴上的偏移。不同尺寸页面混排时，需要补上这个居中偏移。
     */
    private float getCrossAxisPageCenterOffset(
            int pageIndex,
            float currentPageWidth,
            float currentPageHeight,
            boolean swipeVertical
    ) {
        if (pdfView == null) return 0f;

        try {
            int pageCount = pdfView.getPageCount();
            if (pageCount <= 0) return 0f;

            ensurePageSizeCache(pageCount);

            float currentBaseSize = swipeVertical
                    ? pdfView.getPageSize(pageIndex).getWidth()
                    : pdfView.getPageSize(pageIndex).getHeight();
            if (currentBaseSize <= 0f) return 0f;

            float currentScaledSize = swipeVertical ? currentPageWidth : currentPageHeight;
            float scale = currentScaledSize / currentBaseSize;
            float maxBaseSize = swipeVertical ? cachedMaxPageWidth : cachedMaxPageHeight;
            if (maxBaseSize <= 0f) return 0f;

            return Math.max(0f, (maxBaseSize - currentBaseSize) * scale / 2f);
        } catch (Exception ignored) {
            return 0f;
        }
    }


    /**
     * 页面尺寸在一次 PDF 加载周期内保持稳定。缓存最大宽高，避免每个绘制帧都遍历全文页面。
     */
    private void ensurePageSizeCache(int pageCount) {
        if (pageCount == cachedPageCount
                && cachedMaxPageWidth > 0f
                && cachedMaxPageHeight > 0f) {
            return;
        }

        float maxWidth = 0f;
        float maxHeight = 0f;
        for (int i = 0; i < pageCount; i++) {
            maxWidth = Math.max(maxWidth, pdfView.getPageSize(i).getWidth());
            maxHeight = Math.max(maxHeight, pdfView.getPageSize(i).getHeight());
        }

        cachedPageCount = pageCount;
        cachedMaxPageWidth = maxWidth;
        cachedMaxPageHeight = maxHeight;
    }

    public static void sortHighlights(List<ViewerSearchHighlight> highlights) {
        if (highlights == null) return;

        Collections.sort(highlights, (a, b) -> {
            if (a.pageIndex != b.pageIndex) {
                return Integer.compare(a.pageIndex, b.pageIndex);
            }

            if (a.rectInPageRatio == null || b.rectInPageRatio == null) {
                return 0;
            }

            int yCompare = Float.compare(a.rectInPageRatio.top, b.rectInPageRatio.top);
            if (yCompare != 0) return yCompare;

            return Float.compare(a.rectInPageRatio.left, b.rectInPageRatio.left);
        });
    }

    public static class ViewerSearchHighlight {

        /** 同一个逻辑结果的多个矩形共享同一 resultId。 */
        public final String resultId;
        public final int pageIndex;

        /**
         * 页面归一化坐标：原点左上，Y 向下，范围 0..1。
         * 这是推荐用于保存和绘制的稳定坐标。
         */
        public final RectF rectInPageRatio;

        /**
         * 兼容旧版调用：PDFView 初始页面尺寸下的页面局部坐标。
         * 缩放后不要直接绘制此矩形，请使用 {@link #rectInPageRatio} 动态换算。
         */
        @Deprecated
        public final RectF rectInDoc;

        public final PdfSearchSource source;
        public final String keyword;
        public final String matchedText;

        public ViewerSearchHighlight(
                int pageIndex,
                RectF rectInPageRatio,
                RectF rectInDoc,
                PdfSearchSource source,
                String keyword,
                String matchedText
        ) {
            this(null, pageIndex, rectInPageRatio, rectInDoc, source, keyword, matchedText);
        }

        public ViewerSearchHighlight(
                String resultId,
                int pageIndex,
                RectF rectInPageRatio,
                RectF rectInDoc,
                PdfSearchSource source,
                String keyword,
                String matchedText
        ) {
            this.resultId = resultId;
            this.pageIndex = pageIndex;
            this.rectInPageRatio = rectInPageRatio;
            this.rectInDoc = rectInDoc;
            this.source = source;
            this.keyword = keyword;
            this.matchedText = matchedText;
        }
    }
}
