package com.nless.pdf_search_engine.index;

/** 规范化文本及其到原始 token 的映射。 */
final class PdfNormalizedText {
    final String text;
    final int[] normalizedToToken;

    PdfNormalizedText(String text, int[] normalizedToToken) {
        this.text = text != null ? text : "";
        this.normalizedToToken = normalizedToToken != null
                ? normalizedToToken
                : new int[0];
    }
}
