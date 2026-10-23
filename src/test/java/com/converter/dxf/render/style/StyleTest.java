package com.converter.dxf.render.style;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StyleTest {

    @Test
    public void testLineweightConversion() {
        // Concrete 0.50 mm (group 370 = 50)
        double mm50 = DxfLineweight.toMillimeters(50, -1, -1);
        assertEquals(0.50, mm50, 1e-4);

        float pt50 = DxfLineweight.toPdfPoints(50, -1, -1);
        assertEquals(0.50 * (72.0 / 25.4), pt50, 1e-3);

        // BYLAYER inheritance: entity has -1, layer has 70 (0.70 mm)
        float ptByLayer = DxfLineweight.toPdfPoints(DxfLineweight.BYLAYER, 70, -1);
        assertEquals(0.70 * (72.0 / 25.4), ptByLayer, 1e-3);

        // DEFAULT fallback: -3 -> 0.25 mm
        float ptDefault = DxfLineweight.toPdfPoints(DxfLineweight.DEFAULT, -1, -1);
        assertEquals(0.25 * (72.0 / 25.4), ptDefault, 1e-3);
    }

    @Test
    public void testLinetypeContinuous() {
        DxfLinetype continuous = DxfLinetype.CONTINUOUS;
        assertTrue(continuous.isContinuous());
        float[] dashArray = continuous.computePdfDashArray(1.0, 1.0, 1.0);
        assertEquals(0, dashArray.length);
    }

    @Test
    public void testLinetypeHiddenDash() {
        // HIDDEN: 5.0 dash, -2.5 space
        DxfLinetype hidden = new DxfLinetype("HIDDEN", "Dashed", Arrays.asList(5.0, -2.5));
        assertFalse(hidden.isContinuous());

        float[] dashArray = hidden.computePdfDashArray(1.0, 2.0, 1.5);
        // Scale = 1.0 * 2.0 * 1.5 = 3.0
        // Dash: 5.0 * 3.0 = 15.0
        // Space: 2.5 * 3.0 = 7.5
        assertEquals(2, dashArray.length);
        assertEquals(15.0f, dashArray[0], 1e-3);
        assertEquals(7.5f, dashArray[1], 1e-3);
    }

    @Test
    public void testLinetypeCenterWithDot() {
        // CENTER: 10.0 dash, -2.0 space, 0.0 dot, -2.0 space (4 elements)
        DxfLinetype center = new DxfLinetype("CENTER", "Dash Dot", Arrays.asList(10.0, -2.0, 0.0, -2.0));
        float[] dashArray = center.computePdfDashArray(1.0, 1.0, 1.0);
        assertEquals(4, dashArray.length);
        assertEquals(10.0f, dashArray[0], 1e-3);
        assertEquals(2.0f, dashArray[1], 1e-3);
        assertTrue(dashArray[2] > 0.0f); // Dot has minimum visible width
        assertEquals(2.0f, dashArray[3], 1e-3);
    }
}
