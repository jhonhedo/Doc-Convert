package com.converter.dxf.render.text;

import java.awt.geom.Point2D;

/**
 * Calculates exact 2D baseline insertion points and bounding offsets for DXF TEXT and MTEXT.
 */
public final class TextAlignmentHelper {

    private TextAlignmentHelper() {}

    /**
     * Resolves the anchor point and baseline offset for standard DXF TEXT.
     *
     * @param p10 Primary insertion point (groups 10, 20)
     * @param p11 Alignment point (groups 11, 21)
     * @param hAlign Group 72 (0=Left, 1=Center, 2=Right, 3=Aligned, 4=Middle, 5=Fit)
     * @param vAlign Group 73 (0=Baseline, 1=Bottom, 2=Middle, 3=Top)
     * @param textWidth Measured width of string
     * @param textHeight Font cap height (group 40)
     * @return Local (dx, dy) offset from effective anchor point to baseline start (left)
     */
    public static Point2D.Double calculateTextOffset(
            int hAlign, int vAlign, double textWidth, double textHeight) {

        double dx = 0.0;
        double dy = 0.0;

        // Horizontal offset
        switch (hAlign) {
            case 1: // Center
                dx = -textWidth / 2.0;
                break;
            case 2: // Right
                dx = -textWidth;
                break;
            case 4: // Middle
                dx = -textWidth / 2.0;
                break;
            default: // 0=Left, 3=Aligned, 5=Fit
                dx = 0.0;
                break;
        }

        // Vertical offset (dy from anchor to text baseline)
        double descent = textHeight * 0.25; // Standard typographic descent approximation
        switch (vAlign) {
            case 1: // Bottom
                dy = descent;
                break;
            case 2: // Middle
                dy = -(textHeight * 0.5) + descent;
                break;
            case 3: // Top
                dy = -textHeight;
                break;
            default: // 0=Baseline
                if (hAlign == 4) {
                    // Group 72 = 4 (Middle) centers both horizontally and vertically
                    dy = -(textHeight * 0.5) + descent;
                } else {
                    dy = 0.0;
                }
                break;
        }

        return new Point2D.Double(dx, dy);
    }

    /**
     * Determines the effective anchor coordinate for DXF TEXT.
     * DXF Specification: If 72 == 0 and 73 == 0, use p10. Otherwise, use p11.
     */
    public static Point2D.Double getEffectiveAnchor(
            Point2D.Double p10, Point2D.Double p11, int hAlign, int vAlign) {
        if (hAlign == 0 && vAlign == 0) {
            return p10;
        }
        return p11;
    }

    /**
     * Calculates anchor offset for MTEXT based on attachment point (group 71).
     *
     * Attachment codes:
     * 1=TopLeft, 2=TopCenter, 3=TopRight
     * 4=MiddleLeft, 5=MiddleCenter, 6=MiddleRight
     * 7=BottomLeft, 8=BottomCenter, 9=BottomRight
     *
     * @param attachment Group 71 (1..9)
     * @param blockWidth Total measured width of MTEXT block
     * @param blockHeight Total height of MTEXT block (lines * lineHeight)
     * @return Local (dx, dy) offset from group 10/20 to top-left of the text block
     */
    public static Point2D.Double calculateMTextOffset(
            int attachment, double blockWidth, double blockHeight) {

        double dx = 0.0;
        double dy = 0.0;

        // Horizontal alignment
        switch (attachment) {
            case 2: case 5: case 8: // Center
                dx = -blockWidth / 2.0;
                break;
            case 3: case 6: case 9: // Right
                dx = -blockWidth;
                break;
            default: // 1, 4, 7 -> Left
                dx = 0.0;
                break;
        }

        // Vertical alignment (dy offset from insertion point to top edge)
        switch (attachment) {
            case 4: case 5: case 6: // Middle
                dy = blockHeight / 2.0;
                break;
            case 7: case 8: case 9: // Bottom
                dy = blockHeight;
                break;
            default: // 1, 2, 3 -> Top
                dy = 0.0;
                break;
        }

        return new Point2D.Double(dx, dy);
    }

    /**
     * Calculates the baseline offset for a specific line of MTEXT in the text's local coordinate system.
     * In this coordinate system, the X axis points in the text reading direction,
     * and the Y axis points perpendicular upwards.
     * The returned (dx, dy) represents the translation from the MTEXT insertion point (group 10/20)
     * to the left-end baseline of that line.
     */
    public static Point2D.Double calculateMTextBaselineOffset(
            int attachment, double lineWidth, double textHeight, int numLines, int lineIdx) {

        double dx = 0.0;
        switch (attachment) {
            case 2: case 5: case 8: // Center
                dx = -lineWidth / 2.0;
                break;
            case 3: case 6: case 9: // Right
                dx = -lineWidth;
                break;
            default: // 1, 4, 7 -> Left
                dx = 0.0;
                break;
        }

        double lineHeight = textHeight * 1.2;
        double dyFirstLine;
        switch (attachment) {
            case 4: case 5: case 6: // Middle
                dyFirstLine = ((numLines - 1) * 0.5 * lineHeight) - (textHeight * 0.30);
                break;
            case 7: case 8: case 9: // Bottom
                dyFirstLine = ((numLines - 1) * lineHeight) + (textHeight * 0.15);
                break;
            default: // 1, 2, 3 -> Top
                dyFirstLine = -(textHeight * 0.75);
                break;
        }

        double dy = dyFirstLine - (lineIdx * lineHeight);
        return new Point2D.Double(dx, dy);
    }
}

