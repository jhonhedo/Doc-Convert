package com.converter.dxf.render.hatch;

import java.awt.Color;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic rule engine for layer-specific custom hatching.
 * Replicates and extends the Aspose polyline-to-hatch generation logic with dynamic layer registration
 * and color mapping rules based on layer names and AutoCAD color IDs.
 */
public class DynamicHatchRuleEngine {

    private static volatile DynamicHatchRuleEngine defaultInstance;

    private final Set<String> targetLayers = new HashSet<>();
    private final Map<String, Color> customLayerColors = new LinkedHashMap<>();
    private boolean matchBySubstring = true;

    /**
     * Returns the singleton / default instance used when no specific engine is supplied.
     */
    public static DynamicHatchRuleEngine getDefault() {
        if (defaultInstance == null) {
            synchronized (DynamicHatchRuleEngine.class) {
                if (defaultInstance == null) {
                    defaultInstance = new DynamicHatchRuleEngine();
                }
            }
        }
        return defaultInstance;
    }

    /**
     * Sets the default instance.
     */
    public static void setDefault(DynamicHatchRuleEngine engine) {
        defaultInstance = engine;
    }

    /**
     * Creates an engine initialized with default municipal scrutiny layers.
     */
    public DynamicHatchRuleEngine() {
        registerDefaultLayers();
    }

    /**
     * Creates an engine with an optional initial layer list.
     * If withDefaultLayers is false, starts completely empty.
     */
    public DynamicHatchRuleEngine(boolean withDefaultLayers) {
        if (withDefaultLayers) {
            registerDefaultLayers();
        }
    }

    /**
     * Creates an engine with a user-supplied collection of target layers.
     */
    public DynamicHatchRuleEngine(Collection<String> initialLayers) {
        if (initialLayers != null) {
            for (String l : initialLayers) {
                if (l != null && !l.trim().isEmpty()) {
                    targetLayers.add(l.toUpperCase().trim());
                }
            }
        }
    }

    /**
     * Registers default municipal scrutiny layers matching Aspose scrutiny workflows.
     */
    public void registerDefaultLayers() {
        targetLayers.add("ACCESS_WIDTH_M");
        targetLayers.add("ACCESS_WIDTH");
        targetLayers.add("ROAD");
        targetLayers.add("ROAD_WIDENING");
        targetLayers.add("OPEN_PARKING");
        targetLayers.add("COVERED_PARKING");
        targetLayers.add("TWO_WHEELER_PARKING");
        targetLayers.add("VISITOR_PARKING");
        targetLayers.add("SPECIAL_PARKING");
        targetLayers.add("ABMULANCE_PARKING");
        targetLayers.add("BUS_PARKING");
        targetLayers.add("MECH_PARKING");
        targetLayers.add("TERRACE_FLOOR");
        targetLayers.add("BLK_1_TERRACE_FLOOR");
        targetLayers.add("COVERED_AREA");
        targetLayers.add("BLK_1_LVL_0_BLDG_FOOT_PRINT");
        targetLayers.add("BLK_1_FLR_0_COVERED_PARKING");
        targetLayers.add("BLK_1_FLR_0_WASH");
        targetLayers.add("AMENITIES");
        targetLayers.add("PLOTTED_DEVELOPMENT");
        targetLayers.add("RESERVIOR");
        targetLayers.add("PARK_OPEN_SPACE_USE");
        targetLayers.add("BLT_UP_AREA_EXISTING");
    }

    public void addTargetLayer(String layerName) {
        if (layerName != null && !layerName.trim().isEmpty()) {
            targetLayers.add(layerName.toUpperCase().trim());
        }
    }

    public void addTargetLayers(Collection<String> layers) {
        if (layers != null) {
            for (String l : layers) {
                addTargetLayer(l);
            }
        }
    }

    public void addTargetLayers(String... layers) {
        if (layers != null) {
            for (String l : layers) {
                addTargetLayer(l);
            }
        }
    }

    public void removeTargetLayer(String layerName) {
        if (layerName != null) {
            targetLayers.remove(layerName.toUpperCase().trim());
        }
    }

    public void clearTargetLayers() {
        targetLayers.clear();
        customLayerColors.clear();
    }

    public void setTargetLayers(Collection<String> layers) {
        targetLayers.clear();
        if (layers != null) {
            for (String l : layers) {
                addTargetLayer(l);
            }
        }
    }

    public Set<String> getTargetLayers() {
        return Collections.unmodifiableSet(targetLayers);
    }

    /**
     * Registers a custom color mapping for any layer name or substring.
     */
    public void registerLayerColor(String layerPattern, Color color) {
        if (layerPattern != null && color != null) {
            String key = layerPattern.toUpperCase().trim();
            customLayerColors.put(key, color);
            targetLayers.add(key);
        }
    }

    public void setMatchBySubstring(boolean matchBySubstring) {
        this.matchBySubstring = matchBySubstring;
    }

    public boolean isMatchBySubstring() {
        return matchBySubstring;
    }

    /**
     * Determines whether an entity on this layer should receive custom dynamic hatching.
     */
    public boolean shouldHatch(String layerName) {
        if (layerName == null || targetLayers.isEmpty()) {
            return false;
        }
        String uLayer = layerName.toUpperCase().trim();
        // Layer 0 and Defpoints are standard AutoCAD structural/drafting layers and should never be auto-hatched
        if ("0".equals(uLayer) || "DEFPOINTS".equals(uLayer)) {
            return false;
        }
        if (targetLayers.contains(uLayer)) {
            return true;
        }
        if (matchBySubstring) {
            for (String target : targetLayers) {
                // The entity's layer name must contain the target layer keyword (e.g., "BLK_1_ROAD" contains "ROAD")
                if (target.length() >= 3 && uLayer.contains(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolves the exact hatch color based on the Aspose rule set:
     * - AMENITIES (colors 118, 131/132, or other)
     * - PLOTTED_DEVELOPMENT (colors 54, 55, 56, 63, 6)
     * - COVERED_AREA / BLDG_FOOT_PRINT / BLT_UP_AREA: rgb(225, 120, 120)
     * - TERRACE_FLOOR / TERRACE: rgb(255, 165, 0)
     * - RESERVIOR: rgb(0, 70, 180)
     * - PARKING (covered, open, two-wheeler, etc.): rgb(0, 255, 255)
     * - ROAD_WIDENING: rgb(169, 169, 169)
     * - ROAD / INTERNAL_ROAD: rgb(143, 143, 143)
     * - ACCESS_WIDTH: rgb(0, 0, 255)
     * - PARK_OPEN_SPACE_USE: rgb(0, 100, 0)
     * - BLT_UP_AREA_EXISTING: rgb(140, 140, 70)
     * - WASH / OTS / VOID / CUTOUT: rgb(255, 127, 191)
     * - Default: rgb(255, 0, 255)
     */
    public Color resolveHatchColor(String layerName, int colorId) {
        if (layerName == null) {
            return new Color(255, 0, 255);
        }
        String layer = layerName.toUpperCase().trim();

        // 1. Check custom registered layer colors first
        for (Map.Entry<String, Color> entry : customLayerColors.entrySet()) {
            if (layer.equals(entry.getKey()) || (matchBySubstring && layer.contains(entry.getKey()))) {
                return entry.getValue();
            }
        }

        // 2. Standard Aspose Scrutiny Rules
        if (layer.contains("AMENITIES")) {
            if (colorId == 118) {
                return new Color(130, 85, 50);
            } else if (colorId == 131 || colorId == 132) {
                return new Color(0, 100, 0);
            } else {
                return new Color(200, 170, 255);
            }
        } else if (layer.contains("PLOTTED_DEVELOPMENT")) {
            if (colorId == 54 || colorId == 55 || colorId == 56 || colorId == 63 || colorId == 6) {
                return new Color(40, 80, 160);
            } else {
                return null;
            }
        } else if (layer.contains("BLT_UP_AREA_EXISTING")) {
            return new Color(140, 140, 70);
        } else if (layer.contains("COVERED_AREA") || layer.contains("BLDG_FOOT_PRINT") || layer.contains("BLT_UP_AREA")) {
            return new Color(225, 120, 120);
        } else if (layer.contains("TERRACE_FLOOR") || layer.contains("TERRACE")) {
            return new Color(255, 165, 0);
        } else if (layer.contains("RESERVIOR")) {
            return new Color(0, 70, 180);
        } else if (layer.contains("COVERED_PARKING") || layer.contains("OPEN_PARKING")
                || layer.contains("TWO_WHEELER_PARKING") || layer.contains("VISITOR_PARKING")
                || layer.contains("SPECIAL_PARKING") || layer.contains("ABMULANCE_PARKING")
                || layer.contains("BUS_PARKING") || layer.contains("MECH_PARKING")
                || layer.contains("PARKING")) {
            return new Color(0, 255, 255);
        } else if (layer.contains("ROAD_WIDENING")) {
            return new Color(169, 169, 169);
        } else if (layer.contains("ROAD") || layer.matches(".*INTERNAL_ROAD_\\d+.*")) {
            return new Color(143, 143, 143);
        } else if (layer.contains("ACCESS_WIDTH")) {
            return new Color(0, 0, 255);
        } else if (layer.contains("PARK_OPEN_SPACE_USE")) {
            return new Color(0, 100, 0);
        } else if (layer.contains("WASH") || layer.contains("OTS") || layer.contains("VOID") || layer.contains("CUTOUT")) {
            return new Color(255, 127, 191);
        } else {
            return new Color(255, 0, 255);
        }
    }

    /**
     * Static utility matching the Aspose createHatchFromPolyline rule logic directly.
     */
    public static Color createHatchColorFromPolyline(String layerName, int colorId) {
        return getDefault().resolveHatchColor(layerName, colorId);
    }
}
