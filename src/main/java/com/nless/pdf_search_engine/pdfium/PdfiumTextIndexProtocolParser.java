package com.nless.pdf_search_engine.pdfium;

import android.graphics.RectF;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** 文本索引二进制协议解析器。 */
public final class PdfiumTextIndexProtocolParser {

    private static final int MAGIC = 0x50495835; // PIX5
    private static final int VERSION = 1;
    private static final int MAX_PAGES = 100_000;
    private static final int MAX_CHARS_PER_PAGE = 5_000_000;

    private PdfiumTextIndexProtocolParser() {
    }

    public static PdfiumTextIndexReport parse(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return PdfiumTextIndexReport.empty();
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (input.getInt() != MAGIC) return PdfiumTextIndexReport.empty();
            if (input.getInt() != VERSION) return PdfiumTextIndexReport.empty();
            int documentPageCount = safeCount(input.getInt(), MAX_PAGES);
            int pageCount = safeCount(input.getInt(), MAX_PAGES);
            List<PdfiumTextIndexPage> pages = new ArrayList<>(pageCount);

            for (int p = 0; p < pageCount; p++) {
                require(input, 32);
                int pageIndex = input.getInt();
                float pageWidth = input.getFloat();
                float pageHeight = input.getFloat();
                int status = input.getInt();
                int rawChars = input.getInt();
                int visibleChars = input.getInt();
                int validBoxes = input.getInt();
                int charCount = safeCount(input.getInt(), MAX_CHARS_PER_PAGE);
                List<PdfiumTextIndexChar> chars = new ArrayList<>(charCount);

                for (int i = 0; i < charCount; i++) {
                    require(input, 32);
                    int codePoint = input.getInt();
                    int sourceIndex = input.getInt();
                    int flags = input.getInt();
                    input.getInt(); // reserved
                    float left = input.getFloat();
                    float top = input.getFloat();
                    float right = input.getFloat();
                    float bottom = input.getFloat();
                    RectF rect = (flags & 1) != 0
                            ? new RectF(left, top, right, bottom)
                            : null;
                    chars.add(new PdfiumTextIndexChar(codePoint, sourceIndex, rect));
                }

                pages.add(new PdfiumTextIndexPage(
                        pageIndex,
                        pageWidth,
                        pageHeight,
                        status,
                        rawChars,
                        visibleChars,
                        validBoxes,
                        chars
                ));
            }
            return new PdfiumTextIndexReport(documentPageCount, pages);
        } catch (Throwable ignored) {
            return PdfiumTextIndexReport.empty();
        }
    }

    private static void require(ByteBuffer input, int bytes) {
        if (input.remaining() < bytes) throw new IllegalStateException("truncated protocol");
    }

    private static int safeCount(int value, int max) {
        if (value < 0 || value > max) throw new IllegalStateException("bad count");
        return value;
    }
}
