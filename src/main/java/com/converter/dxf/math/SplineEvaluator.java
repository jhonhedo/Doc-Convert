package com.converter.dxf.math;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates DXF NURBS SPLINE entities using de Boor's algorithm.
 */
public final class SplineEvaluator {

    private SplineEvaluator() {}

    private static final class Point4D {
        final double x;
        final double y;
        final double z;
        final double w;

        Point4D(double x, double y, double z, double w) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
        }

        public Point4D lerp(Point4D other, double alpha) {
            return new Point4D(
                (1.0 - alpha) * this.x + alpha * other.x,
                (1.0 - alpha) * this.y + alpha * other.y,
                (1.0 - alpha) * this.z + alpha * other.z,
                (1.0 - alpha) * this.w + alpha * other.w
            );
        }

        public Point2D.Double to2D() {
            double weight = Math.abs(w) < 1e-12 ? 1.0 : w;
            return new Point2D.Double(x / weight, y / weight);
        }
    }

    /**
     * Evaluates a NURBS spline into a smooth Path2D.
     *
     * @param degree Spline degree (group 71, e.g. 3)
     * @param knots Knot vector (group 40)
     * @param controlPoints Control points in 3D (groups 10, 20, 30)
     * @param weights Optional weights for rational splines (group 41), or null
     * @param isClosed Whether the spline is closed (group 70 bit 0)
     * @return Path2D.Double approximating the curve
     */
    public static Path2D.Double evaluateSpline(
            int degree,
            List<Double> knots,
            List<Vector3D> controlPoints,
            List<Double> weights,
            boolean isClosed) {

        Path2D.Double path = new Path2D.Double();
        int n = controlPoints.size() - 1;
        int p = degree;
        int m = knots.size() - 1;

        if (n < p || m < n + p + 1) {
            // Insufficient control points or knots
            if (!controlPoints.isEmpty()) {
                Vector3D first = controlPoints.get(0);
                path.moveTo(first.x(), first.y());
                for (int i = 1; i < controlPoints.size(); i++) {
                    Vector3D pt = controlPoints.get(i);
                    path.lineTo(pt.x(), pt.y());
                }
            }
            return path;
        }

        // Build 4D homogeneous control points
        List<Point4D> cp4d = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            Vector3D pt = controlPoints.get(i);
            double w = (weights != null && i < weights.size()) ? weights.get(i) : 1.0;
            cp4d.add(new Point4D(pt.x() * w, pt.y() * w, pt.z() * w, w));
        }

        double uStart = knots.get(p);
        double uEnd = knots.get(m - p);

        int samplesPerSpan = 20;
        boolean firstPoint = true;

        for (int k = p; k < m - p; k++) {
            double u0 = knots.get(k);
            double u1 = knots.get(k + 1);
            if (u1 - u0 < 1e-9) {
                continue; // Zero-length knot span
            }

            for (int s = 0; s < samplesPerSpan; s++) {
                double u = u0 + (u1 - u0) * (s / (double) samplesPerSpan);
                Point2D.Double pt = deBoor(k, p, u, knots, cp4d).to2D();
                if (firstPoint) {
                    path.moveTo(pt.x, pt.y);
                    firstPoint = false;
                } else {
                    path.lineTo(pt.x, pt.y);
                }
            }
        }

        // Final point at uEnd
        Point2D.Double lastPt = deBoor(m - p - 1, p, uEnd, knots, cp4d).to2D();
        if (firstPoint) {
            path.moveTo(lastPt.x, lastPt.y);
        } else {
            path.lineTo(lastPt.x, lastPt.y);
        }

        if (isClosed) {
            path.closePath();
        }

        return path;
    }

    private static Point4D deBoor(int k, int p, double u, List<Double> knots, List<Point4D> cp4d) {
        Point4D[] d = new Point4D[p + 1];
        for (int j = 0; j <= p; j++) {
            d[j] = cp4d.get(j + k - p);
        }

        for (int r = 1; r <= p; r++) {
            for (int j = p; j >= r; j--) {
                int knotIndex = j + k - p;
                double denom = knots.get(knotIndex + p - r + 1) - knots.get(knotIndex);
                double alpha = Math.abs(denom) < 1e-12 ? 0.0 : (u - knots.get(knotIndex)) / denom;
                d[j] = d[j - 1].lerp(d[j], alpha);
            }
        }

        return d[p];
    }
}
