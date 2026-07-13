package com.nless.pdf_search_engine.index;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OcrLayoutWhitespaceReconstructorTest {

    @Test
    public void tokenGeometryRestoresMissingSpaceBetweenLatinAndChinese() {
        String text = "OCR扫描";
        int[] starts = {0, 1, 2, 3, 4};
        int[] ends = {1, 2, 3, 4, 5};
        float[] boxes = {
                0.02f, 0.10f, 0.10f, 0.90f,
                0.11f, 0.10f, 0.19f, 0.90f,
                0.20f, 0.10f, 0.28f, 0.90f,
                0.34f, 0.10f, 0.43f, 0.90f,
                0.44f, 0.10f, 0.53f, 0.90f
        };

        OcrLayoutWhitespaceReconstructor.TokenSpacingProfile profile =
                OcrLayoutWhitespaceReconstructor.analyzeTokens(
                        text,
                        boxes,
                        starts,
                        ends
                );

        assertFalse(profile.shouldInsertSpaceBefore(1));
        assertFalse(profile.shouldInsertSpaceBefore(2));
        assertTrue(profile.shouldInsertSpaceBefore(3));
        assertFalse(profile.shouldInsertSpaceBefore(4));
    }

    @Test
    public void reversedTokenGeometryStillRestoresSpace() {
        String text = "OCR扫描";
        int[] starts = {0, 1, 2, 3, 4};
        int[] ends = {1, 2, 3, 4, 5};
        float[] boxes = {
                0.90f, 0.10f, 0.98f, 0.90f,
                0.81f, 0.10f, 0.89f, 0.90f,
                0.72f, 0.10f, 0.80f, 0.90f,
                0.57f, 0.10f, 0.66f, 0.90f,
                0.47f, 0.10f, 0.56f, 0.90f
        };

        OcrLayoutWhitespaceReconstructor.TokenSpacingProfile profile =
                OcrLayoutWhitespaceReconstructor.analyzeTokens(
                        text,
                        boxes,
                        starts,
                        ends
                );

        assertTrue(profile.shouldInsertSpaceBefore(3));
    }

    @Test
    public void normalChineseCharacterGapsDoNotBecomeSpaces() {
        String text = "扫描页面";
        int[] starts = {0, 1, 2, 3};
        int[] ends = {1, 2, 3, 4};
        float[] boxes = {
                0.02f, 0.10f, 0.20f, 0.90f,
                0.22f, 0.10f, 0.40f, 0.90f,
                0.42f, 0.10f, 0.60f, 0.90f,
                0.62f, 0.10f, 0.80f, 0.90f
        };

        OcrLayoutWhitespaceReconstructor.TokenSpacingProfile profile =
                OcrLayoutWhitespaceReconstructor.analyzeTokens(
                        text,
                        boxes,
                        starts,
                        ends
                );

        for (int i = 0; i < starts.length; i++) {
            assertFalse(profile.shouldInsertSpaceBefore(i));
        }
    }

    @Test
    public void uniformLetterSpacingIsNotMisreadAsWordSpaces() {
        String text = "TITLE";
        int[] starts = {0, 1, 2, 3, 4};
        int[] ends = {1, 2, 3, 4, 5};
        float[] boxes = {
                0.02f, 0.10f, 0.10f, 0.90f,
                0.14f, 0.10f, 0.22f, 0.90f,
                0.26f, 0.10f, 0.34f, 0.90f,
                0.38f, 0.10f, 0.46f, 0.90f,
                0.50f, 0.10f, 0.58f, 0.90f
        };

        OcrLayoutWhitespaceReconstructor.TokenSpacingProfile profile =
                OcrLayoutWhitespaceReconstructor.analyzeTokens(
                        text,
                        boxes,
                        starts,
                        ends
                );

        for (int i = 0; i < starts.length; i++) {
            assertFalse(profile.shouldInsertSpaceBefore(i));
        }
    }

    @Test
    public void separateBlocksUseSpaceOnlyWhenSameLineAndGapIsMeaningful() {
        int close = OcrLayoutWhitespaceReconstructor.separatorBetweenBlocks(
                "跨行", 10f, 10f, 70f, 40f, 28f,
                "检索", 73f, 11f, 130f, 41f, 28f,
                1000f
        );
        int spaced = OcrLayoutWhitespaceReconstructor.separatorBetweenBlocks(
                "OCR", 10f, 10f, 90f, 40f, 24f,
                "扫描", 110f, 11f, 180f, 41f, 30f,
                1000f
        );
        int nextLine = OcrLayoutWhitespaceReconstructor.separatorBetweenBlocks(
                "OCR", 10f, 10f, 90f, 40f, 24f,
                "扫描", 10f, 70f, 100f, 100f, 30f,
                1000f
        );

        assertEquals(OcrLayoutWhitespaceReconstructor.SEPARATOR_NONE, close);
        assertEquals(OcrLayoutWhitespaceReconstructor.SEPARATOR_SPACE, spaced);
        assertEquals(OcrLayoutWhitespaceReconstructor.SEPARATOR_LINE_BREAK, nextLine);
    }

    @Test
    public void explicitWhitespaceTokenPreventsDuplicateSyntheticSpace() {
        String text = "OCR 扫描";
        int[] starts = {0, 1, 2, 3, 4, 5};
        int[] ends = {1, 2, 3, 4, 5, 6};
        float[] boxes = {
                0.02f, 0.10f, 0.10f, 0.90f,
                0.11f, 0.10f, 0.19f, 0.90f,
                0.20f, 0.10f, 0.28f, 0.90f,
                0.29f, 0.10f, 0.33f, 0.90f,
                0.36f, 0.10f, 0.45f, 0.90f,
                0.46f, 0.10f, 0.55f, 0.90f
        };

        OcrLayoutWhitespaceReconstructor.TokenSpacingProfile profile =
                OcrLayoutWhitespaceReconstructor.analyzeTokens(
                        text,
                        boxes,
                        starts,
                        ends
                );

        assertFalse(profile.shouldInsertSpaceBefore(4));
    }
}
