package com.converter.dxf.render.text;

import org.junit.jupiter.api.Test;
import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TextProcessorTest {

    @Test
    public void testCadSpecialCharacterDecoding() {
        String input = "%%c25 PIPE @ 45%%d WITH %%p0.5 TOLERANCE (100%%%)";
        String expected = "Ø25 PIPE @ 45° WITH ±0.5 TOLERANCE (100%)";
        assertEquals(expected, DxfTextProcessor.decodeCadSpecialChars(input));
    }

    @Test
    public void testMTextFormattingCleanup() {
        String input = "{\\fArial|b0|i0;\\C1;ROOM 101\\P\\A1;AREA: 450 SQ FT}";
        String cleaned = DxfTextProcessor.cleanMText(input);
        assertEquals("ROOM 101\nAREA: 450 SQ FT", cleaned);
    }

    @Test
    public void testMTextWordWrapping() {
        String input = "Long note that wraps across multiple lines in drawing";
        List<String> lines = DxfTextProcessor.wrapText(input, 15.0, 1.0);
        assertTrue(lines.size() >= 3);
        assertEquals("Long note that", lines.get(0));
    }

    @Test
    public void testTextEffectiveAnchorRule() {
        Point2D.Double p10 = new Point2D.Double(10, 20);
        Point2D.Double p11 = new Point2D.Double(50, 60);

        // When 72=0 and 73=0 (Left/Baseline), use p10
        Point2D.Double anchorLeft = TextAlignmentHelper.getEffectiveAnchor(p10, p11, 0, 0);
        assertEquals(10.0, anchorLeft.x);
        assertEquals(20.0, anchorLeft.y);

        // When 72=1 (Center), MUST use p11
        Point2D.Double anchorCenter = TextAlignmentHelper.getEffectiveAnchor(p10, p11, 1, 0);
        assertEquals(50.0, anchorCenter.x);
        assertEquals(60.0, anchorCenter.y);
    }

    @Test
    public void testTextAlignmentOffsets() {
        double width = 100.0;
        double height = 20.0;

        // Center alignment: dx = -50
        Point2D.Double centerOffset = TextAlignmentHelper.calculateTextOffset(1, 0, width, height);
        assertEquals(-50.0, centerOffset.x);
        assertEquals(0.0, centerOffset.y);

        // Right alignment: dx = -100
        Point2D.Double rightOffset = TextAlignmentHelper.calculateTextOffset(2, 0, width, height);
        assertEquals(-100.0, rightOffset.x);
        assertEquals(0.0, rightOffset.y);
    }
}
