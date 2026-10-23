package com.converter.dxf.render.hatch;

import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates vector lines for patterned hatches (e.g. ANSI31, ANSI32, cross-hatching).
 */
public final class HatchPatternGenerator {

    // Default line spacing in drawing units for standard patterns
    public static final double DEFAULT_PATTERN_SPACING = 3.0;
    // Maximum steps per angle to prevent memory/PDF bloat while maintaining high visual density
    public static final int MAX_HATCH_STEPS = 1500;

    private HatchPatternGenerator() {}

    /**
     * Generates a grid of parallel line segments sweeping across a bounding box.
     *
     * @param bounds Bounding box of the hatch boundary path
     * @param angleDeg Hatch angle in degrees (e.g. 45.0 for ANSI31)
     * @param scale Hatch scale factor (group 41)
     * @param isDouble Whether cross-hatching is enabled (group 77)
     * @return List of Line2D segments spanning the bounds
     */
    public static List<Line2D.Double> generatePatternLines(
            Rectangle2D bounds, double angleDeg, double scale, boolean isDouble) {
        return generatePatternLines(bounds, angleDeg, scale, isDouble, -1.0);
    }

    /**
     * Generates a grid of parallel line segments sweeping across a bounding box with optional explicit spacing.
     */
    public static List<Line2D.Double> generatePatternLines(
            Rectangle2D bounds, double angleDeg, double scale, boolean isDouble, double explicitSpacing) {

        List<Line2D.Double> lines = new ArrayList<>();
        if (bounds.isEmpty() || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return lines;
        }

        double spacing;
        if (explicitSpacing > 0) {
            spacing = explicitSpacing;
        } else {
            spacing = (scale <= 0 ? 1.0 : scale) * DEFAULT_PATTERN_SPACING;
        }
        if (spacing <= 1e-6) {
            spacing = 1.0;
        }

        // Generate primary angle lines
        generateAngleLines(bounds, Math.toRadians(angleDeg), spacing, lines);

        // If cross-hatch, generate perpendicular lines (+90 degrees)
        if (isDouble) {
            generateAngleLines(bounds, Math.toRadians(angleDeg + 90.0), spacing, lines);
        }

        return lines;
    }

    private static void generateAngleLines(
            Rectangle2D bounds, double angleRad, double spacing, List<Line2D.Double> lines) {

        double cx = bounds.getCenterX();
        double cy = bounds.getCenterY();
        double radius = Math.hypot(bounds.getWidth(), bounds.getHeight()) / 2.0;
        if (radius <= 1e-4) {
            return;
        }

        // Clamp spacing if it would generate excessive lines
        if (radius / spacing > MAX_HATCH_STEPS) {
            spacing = radius / MAX_HATCH_STEPS;
        }

        // Unit vector parallel to hatch lines
        double ux = Math.cos(angleRad);
        double uy = Math.sin(angleRad);

        // Unit vector perpendicular to hatch lines
        double nx = -uy;
        double ny = ux;

        int numSteps = (int) Math.ceil(radius / spacing);

        for (int i = -numSteps; i <= numSteps; i++) {
            double offset = i * spacing;
            double px = cx + offset * nx;
            double py = cy + offset * ny;

            double x1 = px - radius * ux;
            double y1 = py - radius * uy;
            double x2 = px + radius * ux;
            double y2 = py + radius * uy;

            lines.add(new Line2D.Double(x1, y1, x2, y2));
        }
    }
}
