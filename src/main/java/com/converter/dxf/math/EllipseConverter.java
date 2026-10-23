package com.converter.dxf.math;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Converts DXF ELLIPSE entities into smooth cubic Bézier curves using affine transformation.
 */
public final class EllipseConverter {

    private EllipseConverter() {}

    /**
     * Converts an ellipse into cubic Bézier segments appended to a Path2D.
     *
     * @param cx Center X
     * @param cy Center Y
     * @param majorDx Major axis vector X (relative to center)
     * @param majorDy Major axis vector Y (relative to center)
     * @param ratio Ratio of minor axis to major axis (0.0 to 1.0]
     * @param startParam Start parameter in radians (group 41)
     * @param endParam End parameter in radians (group 42)
     * @return Path2D.Double containing the curve
     */
    public static Path2D.Double createEllipsePath(
            double cx, double cy,
            double majorDx, double majorDy,
            double ratio,
            double startParam, double endParam) {

        Path2D.Double path = new Path2D.Double();

        // Minor axis vector v is perpendicular to major axis u: (-uy * ratio, ux * ratio)
        double vx = -majorDy * ratio;
        double vy = majorDx * ratio;

        double sweep = endParam - startParam;
        if (sweep <= 0.0) {
            sweep += 2.0 * Math.PI;
        }

        // Subdivide into segments of at most PI / 2 radians (90 degrees)
        int segments = (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2.0));
        if (segments < 1) segments = 1;
        double delta = sweep / segments;

        // Start point
        Point2D.Double pStart = mapParamToPoint(cx, cy, majorDx, majorDy, vx, vy, startParam);
        path.moveTo(pStart.x, pStart.y);

        double curT = startParam;
        for (int i = 0; i < segments; i++) {
            double nextT = curT + delta;

            // Optimal cubic Bézier control point factor for arc delta
            double k = (4.0 / 3.0) * Math.tan(delta / 4.0);

            // Unit circle point and tangent at curT
            double cos1 = Math.cos(curT);
            double sin1 = Math.sin(curT);
            double ucp1x = cos1 - k * sin1;
            double ucp1y = sin1 + k * cos1;

            // Unit circle point and tangent at nextT
            double cos2 = Math.cos(nextT);
            double sin2 = Math.sin(nextT);
            double ucp2x = cos2 + k * sin2;
            double ucp2y = sin2 - k * cos2;

            Point2D.Double cp1 = mapCircleCoordsToEllipse(cx, cy, majorDx, majorDy, vx, vy, ucp1x, ucp1y);
            Point2D.Double cp2 = mapCircleCoordsToEllipse(cx, cy, majorDx, majorDy, vx, vy, ucp2x, ucp2y);
            Point2D.Double p2 = mapCircleCoordsToEllipse(cx, cy, majorDx, majorDy, vx, vy, cos2, sin2);

            path.curveTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y);
            curT = nextT;
        }

        // If it's a full ellipse, close the path
        if (Math.abs(sweep - 2.0 * Math.PI) < 1e-5) {
            path.closePath();
        }

        return path;
    }

    private static Point2D.Double mapParamToPoint(
            double cx, double cy, double ux, double uy, double vx, double vy, double t) {
        double cosT = Math.cos(t);
        double sinT = Math.sin(t);
        return mapCircleCoordsToEllipse(cx, cy, ux, uy, vx, vy, cosT, sinT);
    }

    private static Point2D.Double mapCircleCoordsToEllipse(
            double cx, double cy, double ux, double uy, double vx, double vy, double px, double py) {
        double x = cx + px * ux + py * vx;
        double y = cy + px * uy + py * vy;
        return new Point2D.Double(x, y);
    }
}
