package com.nless.pdf_search_engine.core;

/**
 * 与文档内容提取无关的查询选项。
 * 修改这些选项不会让 OCR / 文本层页面索引失效。
 */
public class PdfSearchQueryOptions {

    /** 是否区分大小写。 */
    public boolean caseSensitive = false;

    /** 使用 Unicode NFKC 规范化，覆盖常见全角/半角差异。 */
    public boolean normalizeUnicode = true;

    /** 连续普通空格和制表符折叠为一个空格；换行由 allowCrossLineMatch 单独控制。 */
    public boolean collapseWhitespace = true;

    /**
     * 允许关键词跨 OCR 行或 PDF 文本换行匹配。
     * 开启时只移除换行及其两侧的版面空白，不移除同一行中的普通单词空格。
     */
    public boolean allowCrossLineMatch = true;

    /** 匹配时移除所有空白。适合版面插入了错误空格的 PDF，默认关闭。 */
    public boolean ignoreWhitespaceForMatching = false;

    /**
     * OCR 查询中将字母 O/o 与数字 0 视为同一字符，缓解 OCR 易混淆识别。
     * 仅作用于 OCR 页面；PDF 原生文本层仍保持精确字符语义。默认开启。
     */
    public boolean tolerateOcrOZeroConfusion = true;

    /** 合并英文行末连字符，例如 "search-\nengine"。 */
    public boolean joinHyphenatedLineBreaks = true;

    /** 仅匹配完整英文/数字单词。中文查询通常保持 false。 */
    public boolean wholeWord = false;

    /** 结果前后文字符数。 */
    public int contextCharacters = 24;

    public PdfSearchQueryOptions() {
    }

    public PdfSearchQueryOptions(PdfSearchQueryOptions other) {
        if (other == null) return;
        caseSensitive = other.caseSensitive;
        normalizeUnicode = other.normalizeUnicode;
        collapseWhitespace = other.collapseWhitespace;
        allowCrossLineMatch = other.allowCrossLineMatch;
        ignoreWhitespaceForMatching = other.ignoreWhitespaceForMatching;
        tolerateOcrOZeroConfusion = other.tolerateOcrOZeroConfusion;
        joinHyphenatedLineBreaks = other.joinHyphenatedLineBreaks;
        wholeWord = other.wholeWord;
        contextCharacters = other.contextCharacters;
    }
}
