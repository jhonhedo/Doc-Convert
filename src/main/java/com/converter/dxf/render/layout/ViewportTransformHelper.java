package com.converter.dxf.render.layout;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * Calculates clipping rectangles and transformation matrices for DXF viewports.
 */
public final class ViewportTransformHelper {

    private ViewportTransformHelper() {}

    /**
     * Computes the bounding rectangle of the viewport window on the Paper Space sheet.
     */
    public static Rectangle2D.Double calculateClipRectangle(DxfViewport vp) {
        double xMin = vp.center().x - vp.width() / 2.0;
        double yMin = vp.center().y - vp.height() / 2.0;
        return new Rectangle2D.Double(xMin, yMin, vp.width(), vp.height());
    }

    /**
     * Computes the AffineTransform that maps Model Space coordinates (WCS)
     * into Paper Space sheet coordinates.
     */
    public static AffineTransform calculateModelToPaperTransform(DxfViewport vp) {
        if (vp.viewHeight() <= 0) {
            return new AffineTransform();
        }

        // Viewport scale factor S = paper height / model view height
        double scale = vp.height() / vp.viewHeight();

        // Paper translation to place model viewCenter at paper center
        double tx = vp.center().x - vp.viewCenter().x * scale;
        double ty = vp.center().y - vp.viewCenter().y * scale;

        AffineTransform xform = new AffineTransform();
        xform.translate(tx, ty);
        xform.scale(scale, scale);

        if (Math.abs(vp.twistAngleRad()) > 1e-6) {
            xform.rotate(-vp.twistAngleRad(), vp.viewCenter().x, vp.viewCenter().y);
        }

        return xform;
    }
}
