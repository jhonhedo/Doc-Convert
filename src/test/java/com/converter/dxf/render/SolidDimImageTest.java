package com.converter.dxf.render;

import com.converter.dxf.render.dim.DxfDimensionHelper;
import com.converter.dxf.render.image.DxfImageHelper;
import com.converter.dxf.render.solid.DxfSolidHelper;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SolidDimImageTest {

    @Test
    public void testSolidHourglassFix() {
        // AutoCAD DXF stores quad coordinates as:
        // P0 (top-left), P1 (top-right), P2 (bottom-left), P3 (bottom-right)
        Point2D.Double p0 = new Point2D.Double(0, 10);
        Point2D.Double p1 = new Point2D.Double(10, 10);
        Point2D.Double p2 = new Point2D.Double(0, 0);
        Point2D.Double p3 = new Point2D.Double(10, 0);

        Path2D.Double path = DxfSolidHelper.createSolidPath(p0, p1, p2, p3);

        // Center of the quad (5, 5) MUST be inside the solid fill!
        // In the flawed 0->1->2->3 ordering, (5,5) lies on the crossing self-intersection.
        assertTrue(path.contains(5, 5));
        assertTrue(path.contains(1, 1));
        assertTrue(path.contains(9, 9));
        assertFalse(path.contains(15, 15));
    }

    @Test
    public void testTriangularSolid() {
        Point2D.Double p0 = new Point2D.Double(0, 0);
        Point2D.Double p1 = new Point2D.Double(10, 0);
        Point2D.Double p2 = new Point2D.Double(5, 10);

        Path2D.Double tri = DxfSolidHelper.createSolidPath(p0, p1, p2, null);
        assertTrue(tri.contains(5, 2));
        assertFalse(tri.contains(1, 9));
    }

    @Test
    public void testDimensionAnonymousBlockResolution() {
        Map<String, Object> blockTable = new HashMap<>();
        blockTable.put("*D1", new Object());
        blockTable.put("*D2", new Object());

        assertTrue(DxfDimensionHelper.hasAnonymousBlock("*D1", blockTable));
        assertTrue(DxfDimensionHelper.hasAnonymousBlock(" *d2 ", blockTable)); // Case-insensitive
        assertFalse(DxfDimensionHelper.hasAnonymousBlock("*D99", blockTable));
    }

    @Test
    public void testImageTransformCalculation() {
        Point2D.Double origin = new Point2D.Double(100, 200);
        Point2D.Double uVec = new Point2D.Double(0.1, 0.0); // 0.1 drawing units per pixel
        Point2D.Double vVec = new Point2D.Double(0.0, 0.1);
        double pixelWidth = 800;
        double pixelHeight = 600;

        AffineTransform xform = DxfImageHelper.calculateImageTransform(
            origin, uVec, vVec, pixelWidth, pixelHeight
        );

        // A unit point (1, 1) should map to origin + (800*0.1, 600*0.1) = (180, 260)
        Point2D pt = xform.transform(new Point2D.Double(1.0, 1.0), null);
        assertEquals(180.0, pt.getX(), 1e-3);
        assertEquals(260.0, pt.getY(), 1e-3);
    }
}
