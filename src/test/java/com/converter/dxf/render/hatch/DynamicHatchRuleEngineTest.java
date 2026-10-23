package com.converter.dxf.render.hatch;

import com.converter.dxf.parser.DxfTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DynamicHatchRuleEngineTest {

    private DynamicHatchRuleEngine engine;

    @BeforeEach
    public void setUp() {
        engine = new DynamicHatchRuleEngine();
    }

    @Test
    public void testDefaultMunicipalLayersRegistered() {
        Set<String> layers = engine.getTargetLayers();
        assertTrue(layers.contains("ACCESS_WIDTH_M"));
        assertTrue(layers.contains("ROAD"));
        assertTrue(layers.contains("ROAD_WIDENING"));
        assertTrue(layers.contains("COVERED_PARKING"));
        assertTrue(layers.contains("OPEN_PARKING"));
        assertTrue(layers.contains("TERRACE_FLOOR"));
        assertTrue(layers.contains("COVERED_AREA"));
        assertTrue(layers.contains("AMENITIES"));
        assertTrue(layers.contains("PLOTTED_DEVELOPMENT"));
        assertTrue(layers.contains("PARK_OPEN_SPACE_USE"));
    }

    @Test
    public void testDynamicLayerConfiguration() {
        DynamicHatchRuleEngine customEngine = new DynamicHatchRuleEngine(false);
        assertTrue(customEngine.getTargetLayers().isEmpty());

        // Dynamic layer registration matching user's Aspose code
        customEngine.addTargetLayer("ACCESS_WIDTH_M");
        customEngine.addTargetLayer("ROAD");

        assertEquals(2, customEngine.getTargetLayers().size());
        assertTrue(customEngine.shouldHatch("ROAD"));
        assertTrue(customEngine.shouldHatch("ACCESS_WIDTH_M"));
        assertFalse(customEngine.shouldHatch("WALL"));

        // Batch addition
        customEngine.addTargetLayers(Arrays.asList("CUSTOM_ZONE", "PARKING_AREA"));
        assertEquals(4, customEngine.getTargetLayers().size());

        // Removal
        customEngine.removeTargetLayer("ROAD");
        assertFalse(customEngine.getTargetLayers().contains("ROAD"));

        // Clear
        customEngine.clearTargetLayers();
        assertTrue(customEngine.getTargetLayers().isEmpty());
    }

    @Test
    public void testAsposeColorMappingRules() {
        // AMENITIES
        assertEquals(new Color(130, 85, 50), engine.resolveHatchColor("AMENITIES", 118));
        assertEquals(new Color(0, 100, 0), engine.resolveHatchColor("AMENITIES", 131));
        assertEquals(new Color(0, 100, 0), engine.resolveHatchColor("AMENITIES", 132));
        assertEquals(new Color(200, 170, 255), engine.resolveHatchColor("AMENITIES", 999));

        // PLOTTED_DEVELOPMENT
        assertEquals(new Color(40, 80, 160), engine.resolveHatchColor("PLOTTED_DEVELOPMENT", 54));
        assertEquals(new Color(40, 80, 160), engine.resolveHatchColor("PLOTTED_DEVELOPMENT", 55));
        assertEquals(new Color(40, 80, 160), engine.resolveHatchColor("PLOTTED_DEVELOPMENT", 6));
        assertNull(engine.resolveHatchColor("PLOTTED_DEVELOPMENT", 999));

        // COVERED_AREA
        assertEquals(new Color(225, 120, 120), engine.resolveHatchColor("COVERED_AREA", 0));
        assertEquals(new Color(225, 120, 120), engine.resolveHatchColor("BLK_1_LVL_0_BLDG_FOOT_PRINT", 0));

        // TERRACE_FLOOR
        assertEquals(new Color(255, 165, 0), engine.resolveHatchColor("TERRACE_FLOOR", 0));

        // RESERVIOR
        assertEquals(new Color(0, 70, 180), engine.resolveHatchColor("RESERVIOR", 0));

        // PARKING
        assertEquals(new Color(0, 255, 255), engine.resolveHatchColor("OPEN_PARKING", 0));
        assertEquals(new Color(0, 255, 255), engine.resolveHatchColor("COVERED_PARKING", 0));
        assertEquals(new Color(0, 255, 255), engine.resolveHatchColor("TWO_WHEELER_PARKING", 0));
        assertEquals(new Color(0, 255, 255), engine.resolveHatchColor("ABMULANCE_PARKING", 0));

        // ROAD & ROAD_WIDENING
        assertEquals(new Color(169, 169, 169), engine.resolveHatchColor("ROAD_WIDENING", 0));
        assertEquals(new Color(143, 143, 143), engine.resolveHatchColor("ROAD", 0));
        assertEquals(new Color(143, 143, 143), engine.resolveHatchColor("INTERNAL_ROAD_12", 0));

        // ACCESS_WIDTH
        assertEquals(new Color(0, 0, 255), engine.resolveHatchColor("ACCESS_WIDTH_M", 0));

        // PARK_OPEN_SPACE_USE
        assertEquals(new Color(0, 100, 0), engine.resolveHatchColor("PARK_OPEN_SPACE_USE", 0));

        // BLT_UP_AREA_EXISTING
        assertEquals(new Color(140, 140, 70), engine.resolveHatchColor("BLT_UP_AREA_EXISTING", 0));

        // Fallback default
        assertEquals(new Color(255, 0, 255), engine.resolveHatchColor("UNKNOWN_LAYER", 0));
    }

    @Test
    public void testCustomLayerColorRegistration() {
        engine.registerLayerColor("MY_SPECIAL_ZONE", new Color(10, 20, 30));
        assertTrue(engine.shouldHatch("MY_SPECIAL_ZONE"));
        assertEquals(new Color(10, 20, 30), engine.resolveHatchColor("MY_SPECIAL_ZONE", 0));
    }

    @Test
    public void inspectHandles() throws Exception {
        File dxf = new File("C:\\Users\\johnson.hedo\\Downloads\\NEW LOWER UPPER COMMERCIAL.dxf");
        if (!dxf.exists()) return;
        String content = new String(Files.readAllBytes(dxf.toPath()), StandardCharsets.UTF_8);
        List<DxfTokenizer.Group> groups = DxfTokenizer.tokenize(content);
        for (int i = 0; i < groups.size(); i++) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 5 && ("8F074".equalsIgnoreCase(g.value()) || "9BF17".equalsIgnoreCase(g.value()) || "3EE10".equalsIgnoreCase(g.value()))) {
                String handle = g.value();
                String eType = groups.get(i - 1).value();
                String layer = "";
                for (int j = i; j < i + 30 && j < groups.size(); j++) {
                    if (groups.get(j).code() == 8) {
                        layer = groups.get(j).value();
                        break;
                    }
                }
                System.out.printf("HANDLE: %s, TYPE: %s, LAYER: '%s'%n", handle, eType, layer);
            }
        }
    }
}
