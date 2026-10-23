package com.converter.dxf.render;

import com.converter.dxf.render.layout.DxfViewport;
import com.converter.dxf.render.layout.ViewportTransformHelper;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.*;

public class ViewportTest {

    @Test
    public void testClipRectangle() {
        // Paper center: (200, 150), width: 100, height: 80
        DxfViewport vp = new DxfViewport(
            2, 1,
            new Point2D.Double(200, 150),
            100.0, 80.0,
            new Point2D.Double(500, 500),
            400.0,
            0.0
        );

        assertTrue(vp.isActiveModelViewport());

        Rectangle2D.Double clip = ViewportTransformHelper.calculateClipRectangle(vp);
        assertEquals(150.0, clip.x, 1e-4);
        assertEquals(110.0, clip.y, 1e-4);
        assertEquals(100.0, clip.width, 1e-4);
        assertEquals(80.0, clip.height, 1e-4);
    }

    @Test
    public void testModelToPaperTransform() {
        // Paper center: (100, 100), paper height: 50
        // Model center: (1000, 1000), model view height: 500
        // Scale = 50 / 500 = 0.1
        DxfViewport vp = new DxfViewport(
            2, 1,
            new Point2D.Double(100, 100),
            80.0, 50.0,
            new Point2D.Double(1000, 1000),
            500.0,
            0.0
        );

        AffineTransform xform = ViewportTransformHelper.calculateModelToPaperTransform(vp);

        // Model view center (1000, 1000) MUST map exactly to paper center (100, 100)
        Point2D modelCenter = new Point2D.Double(1000, 1000);
        Point2D paperMapped = xform.transform(modelCenter, null);
        assertEquals(100.0, paperMapped.getX(), 1e-4);
        assertEquals(100.0, paperMapped.getY(), 1e-4);

        // A point offset by +100 model units in X should map to +10 paper units in X (since scale is 0.1)
        Point2D modelOffset = new Point2D.Double(1100, 1000);
        Point2D paperOffsetMapped = xform.transform(modelOffset, null);
        assertEquals(110.0, paperOffsetMapped.getX(), 1e-4);
        assertEquals(100.0, paperOffsetMapped.getY(), 1e-4);
    }
}
