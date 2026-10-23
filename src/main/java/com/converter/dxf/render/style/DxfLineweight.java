package com.converter.dxf.render.style;

/**
 * Handles DXF lineweight resolution (group code 370).
 * Lineweights are stored in 100ths of a millimeter (e.g. 25 = 0.25mm).
 */
public final class DxfLineweight {

    // 1 mm in PDF points (72 points / 25.4 mm)
    public static final double MM_TO_POINTS = 72.0 / 25.4;

    public static final int BYLAYER = -1;
    public static final int BYBLOCK = -2;
    public static final int DEFAULT = -3;

    // Standard AutoCAD default lineweight: 0.25 mm
    public static final double DEFAULT_LINEWEIGHT_MM = 0.25;

    private DxfLineweight() {}

    /**
     * Converts a DXF group 370 value into millimeters.
     *
     * @param dxfWeight The group 370 value (in 100ths of a mm, or -1/-2/-3)
     * @param layerWeight The resolved layer weight if dxfWeight is BYLAYER (-1)
     * @param blockWeight The block context weight if dxfWeight is BYBLOCK (-2)
     * @return Lineweight in millimeters
     */
    public static double toMillimeters(int dxfWeight, int layerWeight, int blockWeight) {
        if (dxfWeight == BYLAYER) {
            return toMillimeters(layerWeight, DEFAULT, DEFAULT);
        }
        if (dxfWeight == BYBLOCK) {
            return toMillimeters(blockWeight, DEFAULT, DEFAULT);
        }
        if (dxfWeight == DEFAULT || dxfWeight < 0) {
            return DEFAULT_LINEWEIGHT_MM;
        }
        if (dxfWeight == 0) {
            // Hairline: 0.05 mm
            return 0.05;
        }
        // Value is in 1/100ths of a millimeter
        return dxfWeight / 100.0;
    }

    /**
     * Converts a DXF group 370 value into PDF points.
     */
    public static float toPdfPoints(int dxfWeight, int layerWeight, int blockWeight) {
        double mm = toMillimeters(dxfWeight, layerWeight, blockWeight);
        float points = (float) (mm * MM_TO_POINTS);
        // Ensure visible minimum on PDF canvas
        return Math.max(0.1f, points);
    }
}
