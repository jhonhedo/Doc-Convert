package com.converter.dxf.render.solid;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Handles DXF SOLID and TRACE polygon assembly.
 * Correctly orders vertices (0 -> 1 -> 3 -> 2) to eliminate the AutoCAD hourglass bug.
 */
public final class DxfSolidHelper {

    private DxfSolidHelper() {}

    /**
     * Builds a closed Path2D for a DXF SOLID.
     *
     * @param p0 First vertex (groups 10, 20)
     * @param p1 Second vertex (groups 11, 21)
     * @param p2 Third vertex (groups 12, 22)
     * @param p3 Fourth vertex (groups 13, 23) - can be null or identical to p2 for triangles
     * @return Path2D.Double ready for filling
     */
    public static Path2D.Double createSolidPath(
            Point2D.Double p0, Point2D.Double p1, Point2D.Double p2, Point2D.Double p3) {

        Path2D.Double path = new Path2D.Double();
        path.moveTo(p0.x, p0.y);
        path.lineTo(p1.x, p1.y);

        boolean isTriangle = (p3 == null) || (Math.abs(p2.x - p3.x) < 1e-9 && Math.abs(p2.y - p3.y) < 1e-9);

        if (isTriangle) {
            // Triangle: 0 -> 1 -> 2 -> close
            path.lineTo(p2.x, p2.y);
        } else {
            // Quadrilateral: AutoCAD stores as top edge (0 -> 1) and bottom edge (2 -> 3).
            // To traverse the perimeter without self-intersecting: 0 -> 1 -> 3 -> 2 -> close.
            path.lineTo(p3.x, p3.y);
            path.lineTo(p2.x, p2.y);
        }

        path.closePath();
        return path;
    }
}
