package com.converter.dxf.render.dim;

import java.util.Map;

/**
 * Resolves DXF DIMENSION entities via their associated anonymous block (*D...).
 */
public final class DxfDimensionHelper {

    private DxfDimensionHelper() {}

    /**
     * Checks if a DIMENSION entity references a valid anonymous block (case-insensitive).
     *
     * @param blockName Name from group 2 (e.g. "*D1")
     * @param blockTable Map of block name to block object
     * @return true if the anonymous block exists in the drawing
     */
    public static boolean hasAnonymousBlock(String blockName, Map<String, ?> blockTable) {
        if (blockName == null || blockName.trim().isEmpty() || blockTable == null) {
            return false;
        }
        String normalized = normalizeBlockName(blockName);
        for (String key : blockTable.keySet()) {
            if (normalizeBlockName(key).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes dimension block names (handles case-insensitivity in DXF blocks).
     */
    public static String normalizeBlockName(String blockName) {
        return (blockName != null) ? blockName.trim().toUpperCase() : "";
    }
}
