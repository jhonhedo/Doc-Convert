package com.converter.dxf.math;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Converts DXF LWPOLYLINE bulge segments (group code 42) into smooth cubic Bézier curves.
 * Bulge b = tan(theta / 4), where theta is the included angle.
 */
public final class BulgeConverter {

    private BulgeConverter() {}

    /**
     * Appends an arc segment defined by a DXF bulge to a Path2D.
     *
     * @param path The path to append to (assumed current point is p1)
     * @param p1 Start point
     * @param p2 End point
     * @param bulge DXF group 42 value
     */
    public static void appendBulge(Path2D.Double path, Point2D.Double p1, Point2D.Double p2, double bulge) {
        if (Math.abs(bulge) < 1e-9) {
            path.lineTo(p2.x, p2.y);
            return;
        }

        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double chordLen = Math.hypot(dx, dy);
        if (chordLen < 1e-9) {
            return;
        }

        // Included angle theta = 4 * atan(bulge)
        double theta = 4.0 * Math.atan(bulge);
        double radius = chordLen * (1.0 + bulge * bulge) / (4.0 * Math.abs(bulge));

        // Vector from chord midpoint to center
        double h = chordLen * (1.0 - bulge * bulge) / (4.0 * bulge);
        double midX = (p1.x + p2.x) / 2.0;
        double midY = (p1.y + p2.y) / 2.0;

        // Normal perpendicular to chord vector (dx, dy): (-dy, dx) / chordLen
        double nx = -dy / chordLen;
        double ny = dx / chordLen;

        double centerX = midX + h * nx;
        double centerY = midY + h * ny;

        double startAngle = Math.atan2(p1.y - centerY, p1.x - centerX);
        double endAngle = Math.atan2(p2.y - centerY, p2.x - centerX);

        // Normalize sweep angle based on bulge sign
        double sweep = theta;
        if (bulge > 0 && sweep < 0) sweep += 2 * Math.PI;
        if (bulge < 0 && sweep > 0) sweep -= 2 * Math.PI;

        appendArcToBezier(path, centerX, centerY, radius, startAngle, sweep);
    }

    /**
     * Subdivides an arc into cubic Bézier segments (each max 90 degrees) for maximum precision.
     */
    public static void appendArcToBezier(Path2D.Double path, double cx, double cy, double r, double startAngle, double sweep) {
        int segments = (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2.0));
        if (segments < 1) segments = 1;

        double segAngle = sweep / segments;
        double k = 4.0 / 3.0 * Math.tan(segAngle / 4.0);

        // Ensure path has initial point
        if (path.getCurrentPoint() == null) {
            path.moveTo(cx + r * Math.cos(startAngle), cy + r * Math.sin(startAngle));
        }

        double curAngle = startAngle;
        for (int i = 0; i < segments; i++) {
            double nextAngle = curAngle + segAngle;

            double cos1 = Math.cos(curAngle);
            double sin1 = Math.sin(curAngle);
            double cos2 = Math.cos(nextAngle);
            double sin2 = Math.sin(nextAngle);

            double p1x = cx + r * cos1;
            double p1y = cy + r * sin1;
            double p2x = cx + r * cos2;
            double p2y = cy + r * sin2;

            double cp1x = p1x - k * r * sin1;
            double cp1y = p1y + k * r * cos1;
            double cp2x = p2x + k * r * sin2;
            double cp2y = p2y - k * r * cos2;

            path.curveTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
            curAngle = nextAngle;
        }
    }
}
