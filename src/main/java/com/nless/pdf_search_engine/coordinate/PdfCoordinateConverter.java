package com.nless.pdf_search_engine.coordinate;

import android.graphics.RectF;

import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchSource;

/**
 * PDF 搜索坐标转换工具。
 *
 * <p>核心约定：</p>
 * <ul>
 *     <li>搜索引擎输出 PDF point 坐标，原点在左下，Y 轴向上。</li>
 *     <li>适配层先转换为页面归一化坐标（0..1，原点左上，Y 轴向下）。</li>
 *     <li>真正绘制时再根据 OnDrawListener 当前传入的 pageWidth/pageHeight 缩放。</li>
 * </ul>
 *
 * <p>归一化坐标是稳定坐标，不受 PDFView 缩放、设备尺寸和页面适配策略影响。</p>
 */
public final class PdfCoordinateConverter {

    private PdfCoordinateConverter() {
    }

    /**
     * 将 PDF point 矩形转换为页面归一化矩形。
     *
     * <p>返回坐标：</p>
     * <ul>
     *     <li>原点左上</li>
     *     <li>Y 轴向下</li>
     *     <li>范围限制在 0..1</li>
     * </ul>
     */
    public static RectF searchRectToPageRatio(PdfSearchRect searchRect) {
        if (searchRect == null || searchRect.rectInPdfPoint == null) return null;
        if (searchRect.pageWidth <= 0f || searchRect.pageHeight <= 0f) return null;

        RectF src = searchRect.rectInPdfPoint;

        float pdfLeft = Math.min(src.left, src.right);
        float pdfRight = Math.max(src.left, src.right);
        float pdfBottom = Math.min(src.top, src.bottom);
        float pdfTop = Math.max(src.top, src.bottom);

        float left = pdfLeft / searchRect.pageWidth;
        float right = pdfRight / searchRect.pageWidth;
        float top = 1f - pdfTop / searchRect.pageHeight;
        float bottom = 1f - pdfBottom / searchRect.pageHeight;

        left = clamp01(left);
        top = clamp01(top);
        right = clamp01(right);
        bottom = clamp01(bottom);

        if (right <= left || bottom <= top) return null;
        return new RectF(left, top, right, bottom);
    }

    /**
     * 将页面归一化矩形转换为当前绘制回调中的页面局部坐标。
     */
    public static RectF pageRatioToPageRect(
            RectF rectInPageRatio,
            float pageWidth,
            float pageHeight
    ) {
        if (rectInPageRatio == null || pageWidth <= 0f || pageHeight <= 0f) {
            return null;
        }

        return new RectF(
                rectInPageRatio.left * pageWidth,
                rectInPageRatio.top * pageHeight,
                rectInPageRatio.right * pageWidth,
                rectInPageRatio.bottom * pageHeight
        );
    }

    /**
     * 兼容旧接口：转换为指定页面尺寸下的 AndroidPdfViewer 页面/文档坐标。
     *
     * <p>注意：高亮长期保存时不要保存此返回值，应保存归一化坐标，并在每次绘制时动态缩放。</p>
     */
    public static RectF searchRectToAndroidPdfViewerDocRect(
            PdfSearchRect searchRect,
            PdfPageInfo pageInfo,
            PdfSearchSource source
    ) {
        if (pageInfo == null || pageInfo.viewPageWidth <= 0f || pageInfo.viewPageHeight <= 0f) {
            return null;
        }

        RectF ratioRect = searchRectToPageRatio(searchRect);
        RectF pageRect = pageRatioToPageRect(
                ratioRect,
                pageInfo.viewPageWidth,
                pageInfo.viewPageHeight
        );
        if (pageRect == null) return null;

        pageRect.offset(0f, pageInfo.pageStartY);
        return pageRect;
    }

    /**
     * 旧版曾在坐标转换阶段二次扩张文本框。现在几何结果保持原样，视觉 padding
     * 应由绘制层明确配置，避免 PDFium/OCR 已经扩张后再次扩张。
     */
    @Deprecated
    public static RectF normalizeTextHighlightRect(RectF rect, float pageHeight) {
        return rect == null ? null : new RectF(rect);
    }

    /**
     * 旧版曾对 OCR 框执行固定上移、限高和横向扩张。该处理会随字号变化产生漂移，
     * 现在保持原始几何结果。
     */
    @Deprecated
    public static RectF normalizeOcrHighlightRect(RectF rect, float pageHeight) {
        return rect == null ? null : new RectF(rect);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
