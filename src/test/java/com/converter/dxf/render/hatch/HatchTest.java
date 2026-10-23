package com.converter.dxf.render.hatch;

import org.junit.jupiter.api.Test;

import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HatchTest {

    @Test
    public void testBoundaryLoopWithIslandEvenOdd() {
        // Outer box: [0, 0] to [100, 100]
        DxfHatchLoop outer = new DxfHatchLoop(true, true);
        outer.addVertex(0, 0, 0);
        outer.addVertex(100, 0, 0);
        outer.addVertex(100, 100, 0);
        outer.addVertex(0, 100, 0);

        // Island hole: [25, 25] to [75, 75]
        DxfHatchLoop hole = new DxfHatchLoop(true, true);
        hole.addVertex(25, 25, 0);
        hole.addVertex(75, 25, 0);
        hole.addVertex(75, 75, 0);
        hole.addVertex(25, 75, 0);

        Path2D.Double composite = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        outer.appendToPath(composite);
        hole.appendToPath(composite);

        // Point inside outer but outside island -> should be contained
        assertTrue(composite.contains(10, 10));
        assertTrue(composite.contains(90, 90));

        // Point inside island hole -> even-odd rule makes it NOT contained (hollow)
        assertFalse(composite.contains(50, 50));

        // Point outside outer -> NOT contained
        assertFalse(composite.contains(150, 150));
    }

    @Test
    public void testPatternLineGeneration() {
        Rectangle2D.Double bounds = new Rectangle2D.Double(0, 0, 100, 100);

        // Single 45-degree pattern
        List<Line2D.Double> lines = HatchPatternGenerator.generatePatternLines(bounds, 45.0, 1.0, false);
        assertFalse(lines.isEmpty());

        // Cross-hatch should generate approximately double lines
        List<Line2D.Double> crossLines = HatchPatternGenerator.generatePatternLines(bounds, 45.0, 1.0, true);
        assertEquals(lines.size() * 2, crossLines.size());
    }
}
