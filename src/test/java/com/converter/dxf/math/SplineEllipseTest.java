package com.converter.dxf.math;

import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SplineEllipseTest {

    @Test
    public void testFullEllipseBounds() {
        // Center: (100, 100), Major: (50, 0) -> a = 50, Minor ratio = 0.5 -> b = 25
        Path2D.Double path = EllipseConverter.createEllipsePath(
            100, 100,
            50, 0,
            0.5,
            0.0, 2.0 * Math.PI
        );

        Rectangle2D bounds = path.getBounds2D();
        // Bounds should be approximately [50, 75] to [150, 125]
        assertEquals(50.0, bounds.getMinX(), 1e-2);
        assertEquals(150.0, bounds.getMaxX(), 1e-2);
        assertEquals(75.0, bounds.getMinY(), 1e-2);
        assertEquals(125.0, bounds.getMaxY(), 1e-2);
    }

    @Test
    public void testEllipticArcSweep() {
        // Quarter ellipse arc: 0 to PI / 2
        Path2D.Double path = EllipseConverter.createEllipsePath(
            0, 0,
            100, 0,
            0.5,
            0.0, Math.PI / 2.0
        );

        Rectangle2D bounds = path.getBounds2D();
        assertTrue(bounds.getMinX() >= -0.01);
        assertTrue(bounds.getMaxX() <= 100.01);
        assertTrue(bounds.getMinY() >= -0.01);
        assertTrue(bounds.getMaxY() <= 50.01);
    }

    @Test
    public void testCubicSplineEvaluation() {
        // Standard clamped cubic B-spline (degree 3) with 4 control points: bezier-like curve
        int degree = 3;
        List<Double> knots = Arrays.asList(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0); // 8 knots
        List<Vector3D> controlPoints = Arrays.asList(
            new Vector3D(0, 0, 0),
            new Vector3D(10, 50, 0),
            new Vector3D(90, 50, 0),
            new Vector3D(100, 0, 0)
        );

        Path2D.Double path = SplineEvaluator.evaluateSpline(degree, knots, controlPoints, null, false);
        assertNotNull(path);
        Rectangle2D bounds = path.getBounds2D();

        // Start point should be near (0, 0) and end point near (100, 0)
        assertEquals(0.0, bounds.getMinX(), 1e-2);
        assertEquals(100.0, bounds.getMaxX(), 1e-2);
        assertTrue(bounds.getMaxY() > 20.0); // Curve bends upward toward control points
    }
}
