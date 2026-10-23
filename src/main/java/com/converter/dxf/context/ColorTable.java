package com.converter.dxf.context;

import java.awt.Color;

/**
 * Standard AutoCAD Color Index (ACI 1-255) to sRGB conversion table.
 */
public final class ColorTable {

    private static final Color[] ACI_COLORS = new Color[256];

    static {
        // Standard primary AutoCAD colors (1-9)
        ACI_COLORS[0] = Color.BLACK; // BYBLOCK placeholder
        ACI_COLORS[1] = new Color(255, 0, 0);     // 1: Red
        ACI_COLORS[2] = new Color(255, 255, 0);   // 2: Yellow
        ACI_COLORS[3] = new Color(0, 255, 0);     // 3: Green
        ACI_COLORS[4] = new Color(0, 255, 255);   // 4: Cyan
        ACI_COLORS[5] = new Color(0, 0, 255);     // 5: Blue
        ACI_COLORS[6] = new Color(255, 0, 255);   // 6: Magenta
        ACI_COLORS[7] = Color.BLACK;              // 7: White/Black (printed on white paper as black)
        ACI_COLORS[8] = new Color(128, 128, 128); // 8: Dark Gray
        ACI_COLORS[9] = new Color(192, 192, 192); // 9: Light Gray

        // Generate standard AutoCAD HSV palette for colors 10 - 249
        // AutoCAD distributes hues every 10 indices, with 5 lightness/saturation levels
        for (int i = 10; i <= 249; i++) {
            int hueIndex = i / 10;
            int level = i % 10;

            float hue = (hueIndex - 1) / 24.0f;
            float sat = 1.0f;
            float val = 1.0f;

            switch (level) {
                case 0: sat = 1.0f; val = 1.0f; break;
                case 1: sat = 1.0f; val = 0.8f; break;
                case 2: sat = 1.0f; val = 0.6f; break;
                case 3: sat = 1.0f; val = 0.45f; break;
                case 4: sat = 1.0f; val = 0.3f; break;
                case 5: sat = 0.5f; val = 1.0f; break;
                case 6: sat = 0.5f; val = 0.8f; break;
                case 7: sat = 0.5f; val = 0.6f; break;
                case 8: sat = 0.5f; val = 0.45f; break;
                case 9: sat = 0.5f; val = 0.3f; break;
            }

            ACI_COLORS[i] = Color.getHSBColor(hue, sat, val);
        }

        // Grayscale ramp 250 - 255
        ACI_COLORS[250] = new Color(51, 51, 51);
        ACI_COLORS[251] = new Color(91, 91, 91);
        ACI_COLORS[252] = new Color(132, 132, 132);
        ACI_COLORS[253] = new Color(173, 173, 173);
        ACI_COLORS[254] = new Color(214, 214, 214);
        ACI_COLORS[255] = Color.WHITE;
    }

    private ColorTable() {}

    /**
     * Resolves an ACI color code (1..255) to a Java Color.
     * Note: ACI 7 on white paper renders as BLACK.
     */
    public static Color getAciColor(int aci) {
        if (aci <= 0 || aci >= 256) {
            return Color.BLACK;
        }
        return ACI_COLORS[aci];
    }
}
