package com.nless.pdf_search_engine.core;

import android.net.Uri;

public interface PdfSearchEngine {

    long search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    );

    void cancel();

    /** 分页边界处暂停；正在执行的单页 native OCR 不会被强行中断。 */
    default void pause() {
    }

    /** 恢复当前暂停任务。 */
    default void resume() {
    }

    void clearCache();
}
