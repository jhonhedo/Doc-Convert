package com.converter.dxf.render.hatch;

import com.converter.dxf.math.BulgeConverter;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a boundary loop (polyline or edge-based) for a DXF HATCH entity.
 */
public class DxfHatchLoop {

    public static final class Vertex {
        public final double x;
        public final double y;
        public final double bulge;

        public Vertex(double x, double y, double bulge) {
            this.x = x;
            this.y = y;
            this.bulge = bulge;
        }

        public double x() { return x; }
        public double y() { return y; }
        public double bulge() { return bulge; }
    }

    private final boolean isPolyline;
    private final boolean isClosed;
    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Path2D.Double> edgePaths = new ArrayList<>();

    public DxfHatchLoop(boolean isPolyline, boolean isClosed) {
        this.isPolyline = isPolyline;
        this.isClosed = isClosed;
    }

    public void addVertex(double x, double y, double bulge) {
        vertices.add(new Vertex(x, y, bulge));
    }

    public void addEdgePath(Path2D.Double edgePath) {
        edgePaths.add(edgePath);
    }

    public boolean isPolyline() {
        return isPolyline;
    }

    public List<Vertex> getVertices() {
        return vertices;
    }

    /**
     * Appends this loop's geometry to a composite Path2D.
     */
    public void appendToPath(Path2D.Double compositePath) {
        if (isPolyline) {
            if (vertices.isEmpty()) return;

            Vertex first = vertices.get(0);
            compositePath.moveTo(first.x(), first.y());

            int count = vertices.size();
            for (int i = 0; i < count - 1; i++) {
                Vertex cur = vertices.get(i);
                Vertex next = vertices.get(i + 1);
                Point2D.Double p1 = new Point2D.Double(cur.x(), cur.y());
                Point2D.Double p2 = new Point2D.Double(next.x(), next.y());
                BulgeConverter.appendBulge(compositePath, p1, p2, cur.bulge());
            }

            if (isClosed && count > 1) {
                Vertex last = vertices.get(count - 1);
                Point2D.Double p1 = new Point2D.Double(last.x(), last.y());
                Point2D.Double p2 = new Point2D.Double(first.x(), first.y());
                BulgeConverter.appendBulge(compositePath, p1, p2, last.bulge());
                compositePath.closePath();
            }
        } else {
            // Edge-based boundary loop
            for (Path2D.Double edge : edgePaths) {
                compositePath.append(edge, false);
            }
            if (isClosed) {
                compositePath.closePath();
            }
        }
    }
}
