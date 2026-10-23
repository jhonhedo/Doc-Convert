package com.converter.dxf.render.layout;

import java.awt.geom.Point2D;

/**
 * Represents a DXF VIEWPORT entity in paper space.
 */
public final class DxfViewport {
    public final int id;                  // Group 69 (1 = sheet, > 1 = model window)
    public final int status;              // Group 68 (> 0 is active)
    public final Point2D.Double center;   // Groups 10, 20 (Paper space center)
    public final double width;            // Group 40 (Paper space width)
    public final double height;           // Group 41 (Paper space height)
    public final Point2D.Double viewCenter;// Groups 12, 22 (Model space center)
    public final double viewHeight;       // Group 45 (Model space view height)
    public final double twistAngleRad;    // Group 51 (View twist angle in radians)

    public DxfViewport(
            int id,
            int status,
            Point2D.Double center,
            double width,
            double height,
            Point2D.Double viewCenter,
            double viewHeight,
            double twistAngleRad) {
        this.id = id;
        this.status = status;
        this.center = center;
        this.width = width;
        this.height = height;
        this.viewCenter = viewCenter;
        this.viewHeight = viewHeight;
        this.twistAngleRad = twistAngleRad;
    }

    public int id() { return id; }
    public int status() { return status; }
    public Point2D.Double center() { return center; }
    public double width() { return width; }
    public double height() { return height; }
    public Point2D.Double viewCenter() { return viewCenter; }
    public double viewHeight() { return viewHeight; }
    public double twistAngleRad() { return twistAngleRad; }

    public boolean isActiveModelViewport() {
        return id > 1 && status > 0 && width > 0 && height > 0 && viewHeight > 0;
    }
}
