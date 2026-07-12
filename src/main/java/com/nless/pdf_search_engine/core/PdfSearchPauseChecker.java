package com.nless.pdf_search_engine.core;

/**
 * 分页任务在页与页之间调用。返回 false 表示任务已取消。
 */
public interface PdfSearchPauseChecker {
    boolean awaitIfPaused();
}
