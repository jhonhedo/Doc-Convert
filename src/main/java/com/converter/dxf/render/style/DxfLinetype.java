package com.converter.dxf.render.style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a DXF Linetype (LTYPE table entry) and converts dash patterns into PDF stroke dash arrays.
 */
public class DxfLinetype {

    public static final DxfLinetype CONTINUOUS = new DxfLinetype("CONTINUOUS", "Solid line", Collections.emptyList());

    private final String name;
    private final String description;
    private final List<Double> elements; // Pattern lengths from group 49

    public DxfLinetype(String name, String description, List<Double> elements) {
        this.name = name != null ? name.toUpperCase() : "CONTINUOUS";
        this.description = description != null ? description : "";
        this.elements = elements != null ? new ArrayList<>(elements) : Collections.emptyList();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<Double> getElements() {
        return elements;
    }

    public boolean isContinuous() {
        return elements.isEmpty() || "CONTINUOUS".equalsIgnoreCase(name) || "SOLID".equalsIgnoreCase(name);
    }

    /**
     * Computes the PDF float[] dash array for PDPageContentStream.setLineDashPattern.
     *
     * @param entityScale Group 48 scale factor (default 1.0)
     * @param globalLtScale $LTSCALE global drawing scale (default 1.0)
     * @param modelToPageScale Scale factor from drawing units to PDF points
     * @return float[] dash array or empty array if continuous
     */
    public float[] computePdfDashArray(double entityScale, double globalLtScale, double modelToPageScale) {
        if (isContinuous()) {
            return new float[0];
        }

        double totalScale = (entityScale <= 0 ? 1.0 : entityScale)
                          * (globalLtScale <= 0 ? 1.0 : globalLtScale)
                          * modelToPageScale;

        List<Float> dashes = new ArrayList<>();
        for (double elem : elements) {
            float len = (float) (Math.abs(elem) * totalScale);
            if (elem == 0.0) {
                // In CAD, 0.0 represents a dot. With round caps in PDFBox, 0.01f creates a crisp dot.
                len = 0.5f;
            }
            dashes.add(Math.max(0.1f, len));
        }

        // PDF dash array requires an even number of elements if pattern has odd count
        if (dashes.size() % 2 != 0) {
            // Duplicate the pattern once to make it even
            int originalSize = dashes.size();
            for (int i = 0; i < originalSize; i++) {
                dashes.add(dashes.get(i));
            }
        }

        float[] result = new float[dashes.size()];
        for (int i = 0; i < dashes.size(); i++) {
            result[i] = dashes.get(i);
        }
        return result;
    }
}
