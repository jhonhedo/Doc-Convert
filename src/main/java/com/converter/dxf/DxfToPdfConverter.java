package com.converter.dxf;

import com.converter.dxf.context.ColorTable;
import com.converter.dxf.math.ArbitraryAxis;
import com.converter.dxf.math.BulgeConverter;
import com.converter.dxf.math.EllipseConverter;
import com.converter.dxf.math.SplineEvaluator;
import com.converter.dxf.math.Vector3D;
import com.converter.dxf.parser.DxfTokenizer;
import com.converter.dxf.render.dim.DxfDimensionHelper;
import com.converter.dxf.render.hatch.DynamicHatchRuleEngine;
import com.converter.dxf.render.hatch.HatchPatternGenerator;
import com.converter.dxf.render.layout.DxfViewport;
import com.converter.dxf.render.layout.ViewportTransformHelper;
import com.converter.dxf.render.solid.DxfSolidHelper;
import com.converter.dxf.render.style.DxfLineweight;
import com.converter.dxf.render.text.DxfTextProcessor;
import com.converter.dxf.render.text.TextAlignmentHelper;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import com.converter.dxf.render.image.DxfImageHelper;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * Clean-room DXF to Vector PDF Converter supporting full CAD layers, blocks, and entity pipelines.
 */
public class DxfToPdfConverter {

    public static final class LayerInfo {
        public final String name;
        public final int colorAci;
        public final int lineweight;
        public final boolean isOff;
        public final boolean isFrozen;
        public final boolean isPlotting;

        public LayerInfo(String name, int colorAci, int lineweight, boolean isOff, boolean isFrozen, boolean isPlotting) {
            this.name = name;
            this.colorAci = colorAci;
            this.lineweight = lineweight;
            this.isOff = isOff;
            this.isFrozen = isFrozen;
            this.isPlotting = isPlotting;
        }

        public String name() { return name; }
        public int colorAci() { return colorAci; }
        public int lineweight() { return lineweight; }
        public boolean isOff() { return isOff; }
        public boolean isFrozen() { return isFrozen; }
        public boolean isPlotting() { return isPlotting; }
    }

    public static final class BlockDef {
        public final String name;
        public final Point2D.Double basePoint;
        public final List<DxfShape> shapes;

        public BlockDef(String name, Point2D.Double basePoint, List<DxfShape> shapes) {
            this.name = name;
            this.basePoint = basePoint;
            this.shapes = shapes;
        }

        public String name() { return name; }
        public Point2D.Double basePoint() { return basePoint; }
        public List<DxfShape> shapes() { return shapes; }
    }

    public static final class PaperLayoutDef {
        public final String name;
        public final int tabOrder;
        public final Rectangle2D limits;
        public final List<DxfViewport> viewports;
        public final List<DxfShape> paperShapes;
        public final double plotPaperWidth;
        public final double plotPaperHeight;

        public PaperLayoutDef(String name, int tabOrder, Rectangle2D limits, List<DxfViewport> viewports, List<DxfShape> paperShapes) {
            this(name, tabOrder, limits, viewports, paperShapes, 0.0, 0.0);
        }

        public PaperLayoutDef(String name, int tabOrder, Rectangle2D limits, List<DxfViewport> viewports, List<DxfShape> paperShapes, double plotPaperWidth, double plotPaperHeight) {
            this.name = name;
            this.tabOrder = tabOrder;
            this.limits = limits;
            this.viewports = viewports;
            this.paperShapes = paperShapes;
            this.plotPaperWidth = plotPaperWidth;
            this.plotPaperHeight = plotPaperHeight;
        }

        public String name() { return name; }
        public int tabOrder() { return tabOrder; }
        public Rectangle2D limits() { return limits; }
        public List<DxfViewport> viewports() { return viewports; }
        public List<DxfShape> paperShapes() { return paperShapes; }
        public double plotPaperWidth() { return plotPaperWidth; }
        public double plotPaperHeight() { return plotPaperHeight; }
    }

    public static final class ImageDef {
        public final String handle;
        public final String filePath;
        public final double widthPx;
        public final double heightPx;

        public ImageDef(String handle, String filePath, double widthPx, double heightPx) {
            this.handle = handle;
            this.filePath = filePath;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }

        public String handle() { return handle; }
        public String filePath() { return filePath; }
        public double widthPx() { return widthPx; }
        public double heightPx() { return heightPx; }
    }

    public interface DxfShape {
        Rectangle2D getBounds();
        void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException;
        default void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
            render(cs, xform, scale);
        }
        default void render(PDDocument document, PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
            render(cs, xform, scale, inheritedColor);
        }
    }

    public static class DxfRasterImageShape implements DxfShape {
        private final BufferedImage image;
        private final AffineTransform localMatrix;
        private final Rectangle2D bounds;
        private PDImageXObject cachedPdImage;
        private PDDocument cachedDoc;

        public DxfRasterImageShape(BufferedImage image, AffineTransform localMatrix) {
            this.image = image;
            this.localMatrix = localMatrix;
            Path2D.Double unitSquare = new Path2D.Double();
            unitSquare.moveTo(0, 0);
            unitSquare.lineTo(1, 0);
            unitSquare.lineTo(1, 1);
            unitSquare.lineTo(0, 1);
            unitSquare.closePath();
            this.bounds = unitSquare.createTransformedShape(localMatrix).getBounds2D();
        }

        public BufferedImage getImage() {
            return image;
        }

        public AffineTransform getLocalMatrix() {
            return localMatrix;
        }

        @Override
        public Rectangle2D getBounds() {
            return bounds;
        }

        @Override
        public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
            // Fallback when called without PDDocument
        }

        private static BufferedImage optimizeImage(BufferedImage src) {
            if (src == null) return null;
            int maxDim = 2048;
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= maxDim && h <= maxDim) {
                return src;
            }
            double scale = Math.min((double) maxDim / w, (double) maxDim / h);
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));
            int type = (src.getType() == BufferedImage.TYPE_CUSTOM || src.getType() == 0)
                    ? (src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB)
                    : src.getType();
            BufferedImage scaled = new BufferedImage(newW, newH, type);
            java.awt.Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, newW, newH, null);
            g2.dispose();
            return scaled;
        }

        @Override
        public void render(PDDocument document, PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
            if (document == null || image == null) {
                return;
            }
            if (cachedPdImage == null || cachedDoc != document) {
                BufferedImage opt = optimizeImage(image);
                if (opt != null && !opt.getColorModel().hasAlpha()) {
                    try {
                        cachedPdImage = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, opt, 0.85f);
                    } catch (Exception e) {
                        cachedPdImage = LosslessFactory.createFromImage(document, opt);
                    }
                } else if (opt != null) {
                    cachedPdImage = LosslessFactory.createFromImage(document, opt);
                }
                cachedDoc = document;
            }

            AffineTransform total = new AffineTransform(xform);
            total.concatenate(localMatrix);

            Matrix pdfMatrix = new Matrix(
                (float) total.getScaleX(), (float) total.getShearY(),
                (float) total.getShearX(), (float) total.getScaleY(),
                (float) total.getTranslateX(), (float) total.getTranslateY()
            );

            cs.saveGraphicsState();
            cs.drawImage(cachedPdImage, pdfMatrix);
            cs.restoreGraphicsState();
        }
    }

    public static void convertFile(File dxfFile, File pdfFile) throws IOException {
        convertFile(dxfFile, pdfFile, DynamicHatchRuleEngine.getDefault());
    }

    public static void convertFile(File dxfFile, File pdfFile, Collection<String> targetLayers) throws IOException {
        convertFile(dxfFile, pdfFile, new DynamicHatchRuleEngine(targetLayers));
    }

    public static void convertFile(File dxfFile, File pdfFile, DynamicHatchRuleEngine hatchEngine) throws IOException {
        String content = new String(Files.readAllBytes(dxfFile.toPath()), StandardCharsets.UTF_8);
        try (PDDocument doc = convertToPdfDocument(content, hatchEngine, dxfFile.getParentFile())) {
            File parent = pdfFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                doc.save(fos);
            }
        }
    }

    public static byte[] convertToPdf(String dxfContent) throws IOException {
        return convertToPdf(dxfContent, DynamicHatchRuleEngine.getDefault(), null);
    }

    public static byte[] convertToPdf(String dxfContent, Collection<String> targetLayers) throws IOException {
        return convertToPdf(dxfContent, new DynamicHatchRuleEngine(targetLayers), null);
    }

    public static byte[] convertToPdf(String dxfContent, DynamicHatchRuleEngine hatchEngine) throws IOException {
        return convertToPdf(dxfContent, hatchEngine, null);
    }

    public static byte[] convertToPdf(String dxfContent, DynamicHatchRuleEngine hatchEngine, File baseDir) throws IOException {
        try (PDDocument doc = convertToPdfDocument(dxfContent, hatchEngine, baseDir)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    public static PDDocument convertToPdfDocument(String dxfContent) throws IOException {
        return convertToPdfDocument(dxfContent, DynamicHatchRuleEngine.getDefault(), null);
    }

    public static PDDocument convertToPdfDocument(String dxfContent, Collection<String> targetLayers) throws IOException {
        return convertToPdfDocument(dxfContent, new DynamicHatchRuleEngine(targetLayers), null);
    }

    public static void registerTargetHatchLayers(Collection<String> layers) {
        DynamicHatchRuleEngine.getDefault().addTargetLayers(layers);
    }

    public static void addTargetHatchLayer(String layer) {
        DynamicHatchRuleEngine.getDefault().addTargetLayer(layer);
    }

    public static void clearTargetHatchLayers() {
        DynamicHatchRuleEngine.getDefault().clearTargetLayers();
    }

    public static Set<String> getTargetHatchLayers() {
        return DynamicHatchRuleEngine.getDefault().getTargetLayers();
    }

    public static PDDocument convertToPdfDocument(File dxfFile) throws IOException {
        return convertToPdfDocument(dxfFile, DynamicHatchRuleEngine.getDefault());
    }

    public static PDDocument convertToPdfDocument(File dxfFile, DynamicHatchRuleEngine hatchEngine) throws IOException {
        String content = new String(Files.readAllBytes(dxfFile.toPath()), StandardCharsets.UTF_8);
        return convertToPdfDocument(content, hatchEngine, dxfFile.getParentFile());
    }

    public static PDDocument convertToPdfDocument(String dxfContent, DynamicHatchRuleEngine hatchEngine) throws IOException {
        return convertToPdfDocument(dxfContent, hatchEngine, null);
    }

    public static PDDocument convertToPdfDocument(String dxfContent, DynamicHatchRuleEngine hatchEngine, File baseDir) throws IOException {
        DynamicHatchRuleEngine engine = (hatchEngine != null) ? hatchEngine : DynamicHatchRuleEngine.getDisabledEngine();
        List<DxfTokenizer.Group> groups = DxfTokenizer.tokenize(dxfContent);

        // Header extents ($EXTMIN and $EXTMAX)
        double extMinX = Double.NaN, extMinY = Double.NaN, extMaxX = Double.NaN, extMaxY = Double.NaN;
        for (int i = 0; i < groups.size(); i++) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 9 && "$EXTMIN".equalsIgnoreCase(g.value())) {
                while (i + 1 < groups.size() && groups.get(i + 1).code() != 9 && groups.get(i + 1).code() != 0) {
                    i++;
                    if (groups.get(i).code() == 10) extMinX = groups.get(i).asDouble();
                    if (groups.get(i).code() == 20) extMinY = groups.get(i).asDouble();
                }
            }
            if (g.code() == 9 && "$EXTMAX".equalsIgnoreCase(g.value())) {
                while (i + 1 < groups.size() && groups.get(i + 1).code() != 9 && groups.get(i + 1).code() != 0) {
                    i++;
                    if (groups.get(i).code() == 10) extMaxX = groups.get(i).asDouble();
                    if (groups.get(i).code() == 20) extMaxY = groups.get(i).asDouble();
                }
            }
            if (g.code() == 0 && "ENDSEC".equalsIgnoreCase(g.value())) {
                break;
            }
        }

        Map<String, LayerInfo> layerTable = parseLayers(groups);
        Map<String, ImageDef> imageDefs = parseImageDefs(groups);
        Map<String, BlockDef> blockTable = parseBlocks(groups, layerTable, engine, imageDefs, baseDir);
        List<DxfShape> shapes = parseEntitiesSection(groups, layerTable, blockTable, engine, imageDefs, baseDir);

        // Compute drawing bounding box
        Rectangle2D totalBounds = null;
        for (DxfShape shape : shapes) {
            Rectangle2D b = shape.getBounds();
            if (b != null && !b.isEmpty() && !Double.isInfinite(b.getWidth()) && !Double.isNaN(b.getWidth())) {
                if (totalBounds == null) {
                    totalBounds = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
                } else {
                    totalBounds.add(b);
                }
            }
        }

        Rectangle2D renderBounds = totalBounds;
        if (!Double.isNaN(extMinX) && !Double.isNaN(extMaxX) && extMaxX > extMinX && extMaxY > extMinY) {
            if (totalBounds == null || totalBounds.isEmpty()
                    || totalBounds.getWidth() > (extMaxX - extMinX) * 2.0
                    || totalBounds.getHeight() > (extMaxY - extMinY) * 2.0) {
                renderBounds = new Rectangle2D.Double(extMinX, extMinY, extMaxX - extMinX, extMaxY - extMinY);
            }
        }

        if (renderBounds == null || renderBounds.isEmpty()) {
            renderBounds = new Rectangle2D.Double(0, 0, 100, 100);
        }

        // Auto-select orientation based on aspect ratio with Aspose CAD vector sheet size (14390.0 x 10179.751 pt)
        boolean isLandscape = renderBounds.getWidth() >= renderBounds.getHeight();
        float modelWdth, modelHt;
        if (isLandscape) {
            modelWdth = 14390.0f;
            modelHt = 10179.751f;
        } else {
            modelWdth = 10179.751f;
            modelHt = 14390.0f;
        }
        PDRectangle pageSize = new PDRectangle(modelWdth, modelHt);

        PDDocument document = new PDDocument();
        boolean success = false;
        try {
            PDPage page = new PDPage(pageSize);
            document.addPage(page);

            // Margins: 28.35 points (10 mm)
            float margin = 28.35f;
            float printableWidth = pageSize.getWidth() - 2 * margin;
            float printableHeight = pageSize.getHeight() - 2 * margin;

            double scaleX = printableWidth / Math.max(1e-3, renderBounds.getWidth());
            double scaleY = printableHeight / Math.max(1e-3, renderBounds.getHeight());
            double scale = Math.min(scaleX, scaleY);

            // Center drawing on page
            double pageCenterX = margin + printableWidth / 2.0;
            double pageCenterY = margin + printableHeight / 2.0;
            double drawingCenterX = renderBounds.getCenterX();
            double drawingCenterY = renderBounds.getCenterY();

            AffineTransform xform = new AffineTransform();
            xform.translate(pageCenterX, pageCenterY);
            xform.scale(scale, scale);
            xform.translate(-drawingCenterX, -drawingCenterY);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                for (DxfShape shape : shapes) {
                    shape.render(document, cs, xform, scale, null);
                }
            }

            // Render Paper Space Layouts as additional pages and merge with model space
            List<PaperLayoutDef> layouts = parseLayouts(groups, layerTable, blockTable, engine, imageDefs, baseDir);
            for (PaperLayoutDef layout : layouts) {
                renderLayoutPage(document, layout, shapes, layerTable, renderBounds);
            }

            success = true;
            return document;
        } finally {
            if (!success) {
                try {
                    document.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static Map<String, ImageDef> parseImageDefs(List<DxfTokenizer.Group> groups) {
        Map<String, ImageDef> defs = new HashMap<>();
        int i = 0, n = groups.size();
        while (i < n) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 0 && "IMAGEDEF".equalsIgnoreCase(g.value())) {
                i++;
                String handle = "";
                String filePath = "";
                double w = 0, h = 0;
                while (i < n && groups.get(i).code() != 0) {
                    DxfTokenizer.Group ig = groups.get(i);
                    switch (ig.code()) {
                        case 5: handle = ig.value().toUpperCase(); break;
                        case 1: filePath = ig.value(); break;
                        case 10: w = ig.asDouble(); break;
                        case 20: h = ig.asDouble(); break;
                    }
                    i++;
                }
                if (!handle.isEmpty() && !filePath.isEmpty()) {
                    defs.put(handle, new ImageDef(handle, filePath, w, h));
                }
                continue;
            }
            i++;
        }
        return defs;
    }

    private static BufferedImage loadImageFile(String path, File baseDir) {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path);
        if (f.exists()) {
            try { return ImageIO.read(f); } catch (Exception ignored) {}
        }
        if (baseDir != null) {
            File rel = new File(baseDir, f.getName());
            if (rel.exists()) {
                try { return ImageIO.read(rel); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static List<PaperLayoutDef> parseLayouts(
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            Map<String, BlockDef> blocks,
            DynamicHatchRuleEngine engine,
            Map<String, ImageDef> imageDefs,
            File baseDir) {

        Map<String, String> blockRecordHandleToName = new HashMap<>();
        int n = groups.size();
        for (int i = 0; i < n; i++) {
            if (groups.get(i).code() == 0 && "BLOCK_RECORD".equalsIgnoreCase(groups.get(i).value())) {
                i++;
                String bName = "", handle = "";
                while (i < n && groups.get(i).code() != 0) {
                    DxfTokenizer.Group g = groups.get(i);
                    if (g.code() == 2) bName = g.value();
                    if (g.code() == 5) handle = g.value().toUpperCase();
                    i++;
                }
                if (!bName.isEmpty() && !handle.isEmpty()) {
                    blockRecordHandleToName.put(handle, bName);
                }
                i--;
            }
        }

        class RawLayout {
            String name = "";
            int tabOrder = 0;
            String blockRecordHandle = "";
            String blockName = "";
            double limMinX = 0, limMinY = 0, limMaxX = 0, limMaxY = 0;
            double plotPaperWidth = 0.0;
            double plotPaperHeight = 0.0;
            List<DxfViewport> viewports = new ArrayList<>();
            List<DxfShape> paperShapes = new ArrayList<>();
        }
        Map<String, RawLayout> layoutMap = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            if (groups.get(i).code() == 0 && "LAYOUT".equalsIgnoreCase(groups.get(i).value())) {
                i++;
                RawLayout rl = new RawLayout();
                while (i < n && groups.get(i).code() != 0) {
                    DxfTokenizer.Group g = groups.get(i);
                    switch (g.code()) {
                        case 1: rl.name = g.value(); break;
                        case 71: rl.tabOrder = g.asInt(); break;
                        case 330: rl.blockRecordHandle = g.value().toUpperCase(); break;
                        case 10: rl.limMinX = g.asDouble(); break;
                        case 20: rl.limMinY = g.asDouble(); break;
                        case 11: rl.limMaxX = g.asDouble(); break;
                        case 21: rl.limMaxY = g.asDouble(); break;
                        case 44: rl.plotPaperWidth = g.asDouble(); break;
                        case 45: rl.plotPaperHeight = g.asDouble(); break;
                        case 142: if (rl.plotPaperWidth <= 0) rl.plotPaperWidth = g.asDouble(); break;
                        case 143: if (rl.plotPaperHeight <= 0) rl.plotPaperHeight = g.asDouble(); break;
                    }
                    i++;
                }
                rl.blockName = blockRecordHandleToName.getOrDefault(rl.blockRecordHandle, "");
                if (!"Model".equalsIgnoreCase(rl.name)) {
                    layoutMap.put(rl.name.toUpperCase(), rl);
                }
                i--;
            }
        }

        java.util.function.Function<List<DxfTokenizer.Group>, DxfViewport> parseVp = (vgList) -> {
            int id = 0, status = 0;
            double cx = 0, cy = 0, w = 0, h = 0, vx = 0, vy = 0, vh = 0, twist = 0;
            double ucsX = 0, ucsY = 0;
            for (DxfTokenizer.Group g : vgList) {
                switch (g.code()) {
                    case 69: id = g.asInt(); break;
                    case 68: status = g.asInt(); break;
                    case 10: cx = g.asDouble(); break;
                    case 20: cy = g.asDouble(); break;
                    case 40: w = g.asDouble(); break;
                    case 41: h = g.asDouble(); break;
                    case 12: vx = g.asDouble(); break;
                    case 22: vy = g.asDouble(); break;
                    case 17: ucsX = g.asDouble(); break;
                    case 27: ucsY = g.asDouble(); break;
                    case 45: vh = g.asDouble(); break;
                    case 51: twist = g.asDouble(); break;
                }
            }
            vx += ucsX;
            vy += ucsY;
            return new DxfViewport(id, status, new Point2D.Double(cx, cy), w, h, new Point2D.Double(vx, vy), vh, twist);
        };

        for (int i = 0; i < n; i++) {
            if (groups.get(i).code() == 0 && "BLOCK".equalsIgnoreCase(groups.get(i).value())) {
                i++;
                String bName = "";
                while (i < n && groups.get(i).code() != 0) {
                    if (groups.get(i).code() == 2) bName = groups.get(i).value();
                    i++;
                }
                RawLayout targetLayout = null;
                for (RawLayout l : layoutMap.values()) {
                    if (bName.equalsIgnoreCase(l.blockName)) {
                        targetLayout = l;
                        break;
                    }
                }
                if (targetLayout != null) {
                    while (i < n) {
                        if (groups.get(i).code() == 0 && "ENDBLK".equalsIgnoreCase(groups.get(i).value())) {
                            break;
                        }
                        if (groups.get(i).code() == 0) {
                            String eType = groups.get(i).value().toUpperCase();
                            i++;
                            List<DxfTokenizer.Group> eg = new ArrayList<>();
                            while (i < n && groups.get(i).code() != 0) {
                                eg.add(groups.get(i));
                                i++;
                            }
                            if ("VIEWPORT".equals(eType)) {
                                targetLayout.viewports.add(parseVp.apply(eg));
                            } else {
                                DxfShape s = buildEntityShape(eType, eg, layers, blocks, true, false, engine, 0, imageDefs, baseDir);
                                if (s != null) targetLayout.paperShapes.add(s);
                            }
                            continue;
                        }
                        i++;
                    }
                }
            }
        }

        int entIdx = -1;
        for (int i = 0; i < n; i++) {
            if (groups.get(i).code() == 2 && "ENTITIES".equalsIgnoreCase(groups.get(i).value())) {
                entIdx = i + 1;
                break;
            }
        }
        if (entIdx >= 0) {
            int i = entIdx;
            while (i < n) {
                if (groups.get(i).code() == 0 && "ENDSEC".equalsIgnoreCase(groups.get(i).value())) {
                    break;
                }
                if (groups.get(i).code() == 0) {
                    String eType = groups.get(i).value().toUpperCase();
                    i++;
                    List<DxfTokenizer.Group> eg = new ArrayList<>();
                    int space = 0;
                    String layoutName = "";
                    String ownerHandle = "";
                    while (i < n && groups.get(i).code() != 0) {
                        DxfTokenizer.Group g = groups.get(i);
                        eg.add(g);
                        if (g.code() == 67) space = g.asInt();
                        if (g.code() == 410) layoutName = g.value();
                        if (g.code() == 330) ownerHandle = g.value().toUpperCase();
                        i++;
                    }
                    if (space == 1) {
                        RawLayout targetLayout = null;
                        if (!ownerHandle.isEmpty()) {
                            for (RawLayout l : layoutMap.values()) {
                                if (ownerHandle.equals(l.blockRecordHandle)) {
                                    targetLayout = l;
                                    break;
                                }
                            }
                        }
                        if (targetLayout == null && !layoutName.isEmpty()) {
                            targetLayout = layoutMap.get(layoutName.toUpperCase());
                        }
                        if (targetLayout == null) {
                            targetLayout = layoutMap.values().stream()
                                    .min(Comparator.comparingInt(l -> l.tabOrder))
                                    .orElse(null);
                        }
                        if (targetLayout != null) {
                            if ("VIEWPORT".equals(eType)) {
                                targetLayout.viewports.add(parseVp.apply(eg));
                            } else {
                                DxfShape s = buildEntityShape(eType, eg, layers, blocks, false, false, engine, 0, imageDefs, baseDir);
                                if (s != null) targetLayout.paperShapes.add(s);
                            }
                        }
                    }
                    continue;
                }
                i++;
            }
        }

        List<PaperLayoutDef> result = new ArrayList<>();
        List<RawLayout> sorted = new ArrayList<>(layoutMap.values());
        sorted.sort(Comparator.comparingInt(l -> l.tabOrder));

        for (RawLayout rl : sorted) {
            double limW = rl.limMaxX - rl.limMinX;
            double limH = rl.limMaxY - rl.limMinY;
            Rectangle2D limits = (limW > 0 && limH > 0)
                    ? new Rectangle2D.Double(rl.limMinX, rl.limMinY, limW, limH)
                    : null;
            result.add(new PaperLayoutDef(rl.name, rl.tabOrder, limits, rl.viewports, rl.paperShapes, rl.plotPaperWidth, rl.plotPaperHeight));
        }

        return result;
    }

    private static void renderLayoutPage(
            PDDocument document,
            PaperLayoutDef layout,
            List<DxfShape> modelShapes,
            Map<String, LayerInfo> layers,
            Rectangle2D modelRenderBounds) throws IOException {

        List<DxfViewport> activeVps = layout.viewports().stream()
                .filter(vp -> vp.id() != 1 && vp.width() > 0 && vp.height() > 0 && vp.viewHeight() > 0
                        && (Math.abs(vp.viewCenter().getX() - vp.center().x) > 1e-3
                            || Math.abs(vp.viewCenter().getY() - vp.center().y) > 1e-3
                            || Math.abs(vp.viewHeight() - vp.height()) > 1e-3))
                .collect(Collectors.toList());

        boolean hasVisibleModelShapes = false;
        for (DxfViewport vp : activeVps) {
            if (viewportHasVisibleShapes(vp, modelShapes, modelRenderBounds)) {
                hasVisibleModelShapes = true;
                break;
            }
        }

        boolean hasPaperShapes = false;
        for (DxfShape s : layout.paperShapes()) {
            Rectangle2D b = s.getBounds();
            if (b != null && !b.isEmpty() && (b.getWidth() > 0.01 || b.getHeight() > 0.01)) {
                hasPaperShapes = true;
                break;
            }
        }

        if (!hasVisibleModelShapes && !hasPaperShapes) {
            return;
        }

        Rectangle2D paperBounds = layout.limits();
        if (paperBounds == null || paperBounds.isEmpty()) {
            for (DxfViewport vp : layout.viewports()) {
                if (vp.id() == 1 && vp.width() > 0 && vp.height() > 0) {
                    paperBounds = ViewportTransformHelper.calculateClipRectangle(vp);
                    break;
                }
            }
        }
        if (paperBounds == null || paperBounds.isEmpty()) {
            for (DxfViewport vp : activeVps) {
                Rectangle2D b = ViewportTransformHelper.calculateClipRectangle(vp);
                if (paperBounds == null) paperBounds = b;
                else paperBounds.add(b);
            }
        }
        for (DxfShape s : layout.paperShapes()) {
            Rectangle2D b = s.getBounds();
            if (b != null && !b.isEmpty()) {
                if (paperBounds == null) paperBounds = b;
                else paperBounds.add(b);
            }
        }
        if (paperBounds == null || paperBounds.isEmpty()) {
            paperBounds = new Rectangle2D.Double(0, 0, 100, 100);
        }

        double pageWidth = layout.plotPaperWidth();
        double pageHeight = layout.plotPaperHeight();

        if (pageWidth <= 0 || pageHeight <= 0) {
            for (DxfViewport vp : layout.viewports()) {
                if (vp.id() == 1 && vp.width() > 0 && vp.height() > 0) {
                    pageWidth = vp.width();
                    pageHeight = vp.height();
                    break;
                }
            }
        }
        if ((pageWidth <= 0 || pageHeight <= 0) && paperBounds != null && !paperBounds.isEmpty()) {
            pageWidth = paperBounds.getWidth();
            pageHeight = paperBounds.getHeight();
        }

        if (pageWidth <= 0 || pageHeight <= 0) {
            pageWidth = 1189;
            pageHeight = 841;
        }
        if (pageWidth <= 0 || pageHeight <= 0) {
            return;
        }

        int pageWdth, pageHt;
        if (pageHeight > pageWidth) {
            pageWdth = (int) Math.round(pageHeight);
            pageHt = (int) Math.round(pageWidth);
        } else {
            pageWdth = (int) Math.round(pageWidth);
            pageHt = (int) Math.round(pageHeight);
        }

        // Aspose scales CAD layout sheets to the 200-inch Acrobat vector sheet size (14390.0 pt width)
        // so that municipal scrutiny tables (sized with 400 pt column widths and 550 pt headers) fit in proportion
        float targetW = 14390.0f;
        float targetH = (float) (14390.0 * ((double) pageHt / (double) pageWdth));

        PDRectangle pageSize = new PDRectangle(targetW, targetH);

        PDPage page = new PDPage(pageSize);
        document.addPage(page);

        float margin = 20.0f;
        float printableWidth = pageSize.getWidth() - 2 * margin;
        float printableHeight = pageSize.getHeight() - 2 * margin;

        double scaleX = printableWidth / Math.max(1e-3, paperBounds.getWidth());
        double scaleY = printableHeight / Math.max(1e-3, paperBounds.getHeight());
        double paperScale = Math.min(scaleX, scaleY);

        double pageCenterX = margin + printableWidth / 2.0;
        double pageCenterY = margin + printableHeight / 2.0;
        double paperCenterX = paperBounds.getCenterX();
        double paperCenterY = paperBounds.getCenterY();

        AffineTransform paperToPage = new AffineTransform();
        paperToPage.translate(pageCenterX, pageCenterY);
        paperToPage.scale(paperScale, paperScale);
        paperToPage.translate(-paperCenterX, -paperCenterY);

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            for (DxfViewport vp : activeVps) {
                if (!viewportHasVisibleShapes(vp, modelShapes, modelRenderBounds)) {
                    continue;
                }
                Rectangle2D.Double clipPaper = ViewportTransformHelper.calculateClipRectangle(vp);
                Point2D p1 = paperToPage.transform(new Point2D.Double(clipPaper.x, clipPaper.y), null);
                Point2D p2 = paperToPage.transform(new Point2D.Double(clipPaper.x + clipPaper.width, clipPaper.y + clipPaper.height), null);

                float clipX = (float) Math.min(p1.getX(), p2.getX());
                float clipY = (float) Math.min(p1.getY(), p2.getY());
                float clipW = (float) Math.abs(p2.getX() - p1.getX());
                float clipH = (float) Math.abs(p2.getY() - p1.getY());

                AffineTransform modelToPaper = ViewportTransformHelper.calculateModelToPaperTransform(vp);
                AffineTransform modelToPage = new AffineTransform(paperToPage);
                modelToPage.concatenate(modelToPaper);

                double modelScale = Math.hypot(modelToPage.getScaleX(), modelToPage.getShearY());

                AffineTransform pageToModel = null;
                try {
                    pageToModel = modelToPage.createInverse();
                } catch (Exception ignored) {}

                Rectangle2D modelClipBounds = null;
                if (pageToModel != null) {
                    Rectangle2D expandedClip = new Rectangle2D.Double(clipX - 2, clipY - 2, clipW + 4, clipH + 4);
                    modelClipBounds = pageToModel.createTransformedShape(expandedClip).getBounds2D();
                }

                cs.saveGraphicsState();
                cs.addRect(clipX, clipY, clipW, clipH);
                cs.clip();

                for (DxfShape shape : modelShapes) {
                    if (modelClipBounds != null) {
                        Rectangle2D sb = shape.getBounds();
                        if (sb != null && !sb.isEmpty() && !modelClipBounds.intersects(sb)) {
                            continue;
                        }
                    }
                    shape.render(document, cs, modelToPage, modelScale, null);
                }

                cs.restoreGraphicsState();

                cs.setStrokingColor(Color.LIGHT_GRAY);
                cs.setLineWidth(0.25f);
                cs.addRect(clipX, clipY, clipW, clipH);
                cs.stroke();
            }

            for (DxfShape shape : layout.paperShapes()) {
                shape.render(document, cs, paperToPage, paperScale, null);
            }
        }
    }

    private static boolean viewportHasVisibleShapes(DxfViewport vp, List<DxfShape> modelShapes, Rectangle2D modelRenderBounds) {
        if (vp == null || vp.width() <= 0 || vp.height() <= 0 || vp.viewHeight() <= 0) {
            return false;
        }
        double aspect = vp.height() > 0 ? vp.width() / vp.height() : 1.0;
        double viewWidth = vp.viewHeight() * aspect;
        double radius = Math.hypot(viewWidth, vp.viewHeight()) / 2.0;
        double cx = vp.viewCenter().getX();
        double cy = vp.viewCenter().getY();
        Rectangle2D vpBounds = new Rectangle2D.Double(cx - radius, cy - radius, radius * 2.0, radius * 2.0);

        if (modelRenderBounds != null && !modelRenderBounds.isEmpty() && !modelRenderBounds.intersects(vpBounds)) {
            return false;
        }

        for (DxfShape s : modelShapes) {
            Rectangle2D b = s.getBounds();
            if (b != null && !b.isEmpty()) {
                if (b.getWidth() > 1e6 || b.getHeight() > 1e6) {
                    continue;
                }
                if (b.intersects(vpBounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Map<String, LayerInfo> parseLayers(List<DxfTokenizer.Group> groups) {
        Map<String, LayerInfo> layers = new HashMap<>();
        int i = 0, n = groups.size();

        while (i < n) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 0 && "LAYER".equalsIgnoreCase(g.value())) {
                i++;
                String name = "0";
                int colorAci = 7;
                int flags = 0;
                int plotFlag = 1;
                int layerLw = DxfLineweight.DEFAULT;

                while (i < n && groups.get(i).code() != 0) {
                    DxfTokenizer.Group lg = groups.get(i);
                    switch (lg.code()) {
                        case 2: name = lg.value(); break;
                        case 62: colorAci = lg.asInt(); break;
                        case 70: flags = lg.asInt(); break;
                        case 290: plotFlag = lg.asInt(); break;
                        case 370: layerLw = lg.asInt(); break;
                    }
                    i++;
                }

                boolean isOff = colorAci < 0;
                boolean isFrozen = (flags & 1) != 0;
                boolean isPlotting = (plotFlag != 0) && !"Defpoints".equalsIgnoreCase(name);
                int effectiveColor = Math.abs(colorAci);

                layers.put(name.toUpperCase(), new LayerInfo(name, effectiveColor, layerLw, isOff, isFrozen, isPlotting));
                continue;
            }
            i++;
        }
        return layers;
    }



    private static Map<String, BlockDef> parseBlocks(
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            DynamicHatchRuleEngine engine) {
        return parseBlocks(groups, layers, engine, null, null);
    }

    public static Map<String, BlockDef> parseBlocks(
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            DynamicHatchRuleEngine engine,
            Map<String, ImageDef> imageDefs,
            File baseDir) {

        Map<String, BlockDef> blocks = new HashMap<>();
        int i = 0, n = groups.size();

        while (i < n) {
            if (groups.get(i).code() == 2 && "BLOCKS".equalsIgnoreCase(groups.get(i).value())) {
                i++;
                break;
            }
            i++;
        }

        while (i < n) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 0 && "ENDSEC".equalsIgnoreCase(g.value())) {
                break;
            }

            if (g.code() == 0 && "BLOCK".equalsIgnoreCase(g.value())) {
                i++;
                String blockName = "";
                double bx = 0, by = 0;

                while (i < n && groups.get(i).code() != 0) {
                    DxfTokenizer.Group bg = groups.get(i);
                    if (bg.code() == 2) blockName = bg.value();
                    if (bg.code() == 10) bx = bg.asDouble();
                    if (bg.code() == 20) by = bg.asDouble();
                    i++;
                }

                boolean isDimBlock = blockName.toUpperCase().startsWith("*D");
                List<DxfShape> blockShapes = new ArrayList<>();
                while (i < n) {
                    DxfTokenizer.Group eg = groups.get(i);
                    if (eg.code() == 0 && "ENDBLK".equalsIgnoreCase(eg.value())) {
                        i++;
                        break;
                    }
                    if (eg.code() == 0) {
                        String entityType = eg.value().toUpperCase();
                        i++;
                        List<DxfTokenizer.Group> entityGroups = new ArrayList<>();
                        while (i < n && groups.get(i).code() != 0) {
                            entityGroups.add(groups.get(i));
                            i++;
                        }
                        DxfShape shape = buildEntityShape(entityType, entityGroups, layers, blocks, true, isDimBlock, engine, 0, imageDefs, baseDir);
                        if (shape != null) {
                            blockShapes.add(shape);
                        }
                        continue;
                    }
                    i++;
                }

                if (!blockName.isEmpty()) {
                    blocks.put(blockName.toUpperCase(), new BlockDef(blockName, new Point2D.Double(bx, by), blockShapes));
                }
                continue;
            }
            i++;
        }
        return blocks;
    }

    private static List<DxfShape> parseEntitiesSection(
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            Map<String, BlockDef> blocks,
            DynamicHatchRuleEngine engine) {
        return parseEntitiesSection(groups, layers, blocks, engine, null, null);
    }

    public static List<DxfShape> parseEntitiesSection(
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            Map<String, BlockDef> blocks,
            DynamicHatchRuleEngine engine,
            Map<String, ImageDef> imageDefs,
            File baseDir) {

        List<DxfShape> shapes = new ArrayList<>();
        int i = 0, n = groups.size();

        while (i < n) {
            if (groups.get(i).code() == 2 && "ENTITIES".equalsIgnoreCase(groups.get(i).value())) {
                i++;
                break;
            }
            i++;
        }

        while (i < n) {
            DxfTokenizer.Group g = groups.get(i);
            if (g.code() == 0 && "ENDSEC".equalsIgnoreCase(g.value())) {
                break;
            }

            if (g.code() == 0) {
                String entityType = g.value().toUpperCase();
                i++;
                List<DxfTokenizer.Group> entityGroups = new ArrayList<>();
                while (i < n && groups.get(i).code() != 0) {
                    entityGroups.add(groups.get(i));
                    i++;
                }
                DxfShape shape = buildEntityShape(entityType, entityGroups, layers, blocks, false, false, engine, 0, imageDefs, baseDir);
                if (shape != null) {
                    shapes.add(shape);
                }
                continue;
            }
            i++;
        }
        return shapes;
    }

    public static Path2D.Double parseHatchLoops(List<DxfTokenizer.Group> groups) {
        Path2D.Double composite = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        int i = 0, n = groups.size();

        boolean hasGroup91 = false;
        for (DxfTokenizer.Group g : groups) {
            if (g.code() == 91) {
                hasGroup91 = true;
                break;
            }
        }

        if (!hasGroup91) {
            List<Point2D.Double> loopVertices = new ArrayList<>();
            for (DxfTokenizer.Group g : groups) {
                if (g.code() == 10) loopVertices.add(new Point2D.Double(g.asDouble(), 0));
                if (g.code() == 20 && !loopVertices.isEmpty()) {
                    Point2D.Double last = loopVertices.get(loopVertices.size() - 1);
                    loopVertices.set(loopVertices.size() - 1, new Point2D.Double(last.x, g.asDouble()));
                }
            }
            if (!loopVertices.isEmpty()) {
                composite.moveTo(loopVertices.get(0).x, loopVertices.get(0).y);
                for (int k = 1; k < loopVertices.size(); k++) {
                    composite.lineTo(loopVertices.get(k).x, loopVertices.get(k).y);
                }
                composite.closePath();
            }
            return composite;
        }

        while (i < n && groups.get(i).code() != 91) i++;
        if (i >= n) return composite;

        int numLoops = groups.get(i).asInt();
        i++;

        for (int l = 0; l < numLoops && i < n; l++) {
            while (i < n && groups.get(i).code() != 92) i++;
            if (i >= n) break;

            int loopType = groups.get(i).asInt();
            boolean isPolyline = (loopType & 2) != 0;
            i++;

            if (isPolyline) {
                int isClosed = 1;
                int numVertices = 0;

                while (i < n && groups.get(i).code() != 93) {
                    if (groups.get(i).code() == 73) isClosed = groups.get(i).asInt();
                    i++;
                }
                if (i < n && groups.get(i).code() == 93) {
                    numVertices = groups.get(i).asInt();
                    i++;
                }

                List<Point2D.Double> pts = new ArrayList<>();
                List<Double> bulges = new ArrayList<>();

                for (int v = 0; v < numVertices && i < n; v++) {
                    double vx = 0, vy = 0, bulge = 0;
                    while (i < n && groups.get(i).code() != 10 && groups.get(i).code() != 97 && groups.get(i).code() != 92 && groups.get(i).code() != 75) {
                        i++;
                    }
                    if (i < n && groups.get(i).code() == 10) {
                        vx = groups.get(i).asDouble();
                        i++;
                    }
                    if (i < n && groups.get(i).code() == 20) {
                        vy = groups.get(i).asDouble();
                        i++;
                    }
                    if (i < n && groups.get(i).code() == 42) {
                        bulge = groups.get(i).asDouble();
                        i++;
                    }
                    pts.add(new Point2D.Double(vx, vy));
                    bulges.add(bulge);
                }

                if (!pts.isEmpty()) {
                    composite.moveTo(pts.get(0).x, pts.get(0).y);
                    for (int k = 0; k < pts.size() - 1; k++) {
                        BulgeConverter.appendBulge(composite, pts.get(k), pts.get(k + 1), bulges.get(k));
                    }
                    if (isClosed != 0 && pts.size() > 1) {
                        BulgeConverter.appendBulge(composite, pts.get(pts.size() - 1), pts.get(0), bulges.get(bulges.size() - 1));
                        composite.closePath();
                    }
                }
            } else {
                int numEdges = 0;
                while (i < n && groups.get(i).code() != 93) i++;
                if (i < n && groups.get(i).code() == 93) {
                    numEdges = groups.get(i).asInt();
                    i++;
                }
                for (int e = 0; e < numEdges && i < n; e++) {
                    while (i < n && groups.get(i).code() != 72) i++;
                    if (i >= n) break;
                    int edgeType = groups.get(i).asInt();
                    i++;
                    if (edgeType == 1) { // Line
                        double x1 = 0, y1 = 0, x2 = 0, y2 = 0;
                        while (i < n && groups.get(i).code() != 10) i++;
                        if (i < n) { x1 = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 20) i++;
                        if (i < n) { y1 = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 11) i++;
                        if (i < n) { x2 = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 21) i++;
                        if (i < n) { y2 = groups.get(i).asDouble(); i++; }

                        composite.moveTo(x1, y1);
                        composite.lineTo(x2, y2);
                    } else if (edgeType == 2) { // Arc
                        double cx = 0, cy = 0, r = 0, sa = 0, ea = 0;
                        boolean ccw = true;
                        while (i < n && groups.get(i).code() != 10) i++;
                        if (i < n) { cx = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 20) i++;
                        if (i < n) { cy = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 40) i++;
                        if (i < n) { r = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 50) i++;
                        if (i < n) { sa = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 51) i++;
                        if (i < n) { ea = groups.get(i).asDouble(); i++; }
                        while (i < n && groups.get(i).code() != 73) i++;
                        if (i < n) { ccw = (groups.get(i).asInt() != 0); i++; }

                        double startRad = Math.toRadians(sa);
                        double endRad = Math.toRadians(ea);
                        double sweep = endRad - startRad;
                        if (!ccw && sweep > 0) sweep -= 2 * Math.PI;
                        if (ccw && sweep <= 0) sweep += 2 * Math.PI;
                        BulgeConverter.appendArcToBezier(composite, cx, cy, r, startRad, sweep);
                    }
                }
            }
        }
        return composite;
    }

    public static float resolveEffectiveStrokeWidth(int lineweight, int layerWeight, boolean isDimension) {
        return resolveEffectiveStrokeWidth(lineweight, layerWeight, isDimension, null);
    }

    public static float resolveEffectiveStrokeWidth(int lineweight, int layerWeight, boolean isDimension, String layerName) {
        if (isDimension) {
            // Fine hairline pen for dimension lines, extension lines, tick marks
            return 0.12f;
        }
        String uLayer = (layerName != null) ? layerName.toUpperCase() : "";
        if (uLayer.contains("BORDER") || uLayer.contains("PLOT") || uLayer.contains("BOUNDARY")) {
            return 0.45f;
        }
        if (uLayer.contains("WALL")) {
            return 0.35f;
        }
        if (lineweight == 0 || (lineweight == DxfLineweight.BYLAYER && layerWeight == 0)) {
            return 0.15f;
        }
        if (lineweight < 0 && (layerWeight < 0 || layerWeight == DxfLineweight.DEFAULT)) {
            // Crisp standard CAD technical line: 0.30 pt ensures bold walls and clean cavities
            return 0.30f;
        }
        double mm = DxfLineweight.toMillimeters(lineweight, layerWeight, -1);
        float rawPt = (float) (mm * DxfLineweight.MM_TO_POINTS);
        float factor = (float) (mm / 0.25);
        float scaled = 0.30f * factor;
        return Math.max(0.18f, Math.min(scaled, rawPt));
    }

    private static DxfShape buildEntityShape(
            String type,
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            Map<String, BlockDef> blocks,
            boolean insideBlock,
            boolean insideDimensionBlock,
            DynamicHatchRuleEngine hatchEngine,
            int parentColorAci) {
        return buildEntityShape(type, groups, layers, blocks, insideBlock, insideDimensionBlock, hatchEngine, parentColorAci, null, null);
    }

    private static DxfShape buildEntityShape(
            String type,
            List<DxfTokenizer.Group> groups,
            Map<String, LayerInfo> layers,
            Map<String, BlockDef> blocks,
            boolean insideBlock,
            boolean insideDimensionBlock,
            DynamicHatchRuleEngine hatchEngine,
            int parentColorAci,
            Map<String, ImageDef> imageDefs,
            File baseDir) {

        // DXF Group Code 60: Entity visibility (optional, default 0 = Visible, 1 = Invisible)
        for (DxfTokenizer.Group g : groups) {
            if (g.code() == 60 && g.asInt() == 1) {
                return null;
            }
            if (g.code() == 1001) {
                break;
            }
        }

        String layerName = "0";
        int colorAci = 256; // 256 = BYLAYER
        int lineweight = DxfLineweight.BYLAYER;
        double x1 = 0, y1 = 0, x2 = 0, y2 = 0, r = 0, startA = 0, endA = 360;
        StringBuilder textVal = new StringBuilder();
        double textHeight = 2.5;
        double rotationDeg = 0.0;
        double sx = 1.0, sy = 1.0;
        String blockRefName = "";
        double majorDx = 0, majorDy = 0, minorRatio = 1.0;
        int splineDegree = 3;
        List<Double> splineKnots = new ArrayList<>();
        List<Vector3D> splineControlPoints = new ArrayList<>();
        int spaceFlag = 0; // 0 = Model Space, 1 = Paper Space
        int hAlign = 0, vAlign = 0, attachment = 1;
        boolean hasP11 = false;

        for (int idx = 0; idx < groups.size(); idx++) {
            DxfTokenizer.Group g = groups.get(idx);
            if (g.code() == 101 || g.code() == 1001) {
                break;
            }
            switch (g.code()) {
                case 8: layerName = g.value(); break;
                case 62: colorAci = g.asInt(); break;
                case 67: spaceFlag = g.asInt(); break;
                case 370: lineweight = g.asInt(); break;
                case 10: x1 = g.asDouble(); break;
                case 20: y1 = g.asDouble(); break;
                case 11: x2 = g.asDouble(); majorDx = g.asDouble(); hasP11 = true; break;
                case 21: y2 = g.asDouble(); majorDy = g.asDouble(); break;
                case 40: r = g.asDouble(); textHeight = g.asDouble(); minorRatio = g.asDouble(); splineKnots.add(g.asDouble()); break;
                case 41: sx = g.asDouble(); break;
                case 42: sy = g.asDouble(); break;
                case 50: rotationDeg = g.asDouble(); break;
                case 51: endA = g.asDouble(); break;
                case 1:
                case 3: textVal.append(g.value()); break;
                case 2: blockRefName = g.value(); break;
                case 71: splineDegree = g.asInt(); attachment = g.asInt(); break;
                case 72: hAlign = g.asInt(); break;
                case 73: vAlign = g.asInt(); break;
            }
        }

        // For MTEXT, group 11/21 is the X-axis direction vector (WCS).
        // If present and non-zero, it defines the true rotation angle.
        if ("MTEXT".equals(type) && hasP11 && (Math.abs(x2) > 1e-5 || Math.abs(y2) > 1e-5)) {
            rotationDeg = Math.toDegrees(Math.atan2(y2, x2));
        }
        rotationDeg = (rotationDeg % 360.0 + 360.0) % 360.0;

        // Skip paper space entities when rendering model space
        if (!insideBlock && spaceFlag == 1) {
            return null;
        }

        // Layer visibility check
        LayerInfo layer = layers.get(layerName.toUpperCase());
        if (layer != null) {
            if (layer.isOff() || layer.isFrozen() || !layer.isPlotting()) {
                return null;
            }
        }

        // Color resolution (BYBLOCK and BYLAYER inheritance)
        int resolvedAci = colorAci;
        if (resolvedAci == 0 && insideBlock && parentColorAci > 0 && parentColorAci < 256) {
            resolvedAci = parentColorAci;
        } else if (resolvedAci == 256 || resolvedAci <= 0) {
            resolvedAci = (layer != null) ? layer.colorAci() : 7;
        }
        String uLayer = layerName.toUpperCase();
        boolean isColumn = uLayer.equals("COLUMN") || uLayer.contains("COLUMN") || uLayer.contains("PILLAR");
        final Color finalColor = isColumn ? Color.BLACK : ColorTable.getAciColor(resolvedAci);
        final boolean isByBlock = (colorAci == 0);
        int layerWeight = (layer != null) ? layer.lineweight() : DxfLineweight.DEFAULT;
        boolean isDim = "DIMENSION".equals(type)
                || insideDimensionBlock
                || uLayer.contains("DIM")
                || uLayer.contains("DIST_EXIT");
        final float finalStrokeWidth = resolveEffectiveStrokeWidth(lineweight, layerWeight, isDim, layerName);

        switch (type) {
            case "LINE": {
                final double fx1 = x1, fy1 = y1, fx2 = x2, fy2 = y2;
                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        Path2D.Double p = new Path2D.Double();
                        p.moveTo(fx1, fy1);
                        p.lineTo(fx2, fy2);
                        return p.getBounds2D();
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(finalStrokeWidth);
                        Point2D p1 = xform.transform(new Point2D.Double(fx1, fy1), null);
                        Point2D p2 = xform.transform(new Point2D.Double(fx2, fy2), null);
                        cs.moveTo((float) p1.getX(), (float) p1.getY());
                        cs.lineTo((float) p2.getX(), (float) p2.getY());
                        cs.stroke();
                    }
                };
            }
            case "POINT": {
                final double px = x1, py = y1;
                return new DxfShape() {
                    public Rectangle2D getBounds() { return new Rectangle2D.Double(px, py, 0.1, 0.1); }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Point2D p = xform.transform(new Point2D.Double(px, py), null);
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(1.0f);
                        float s = 1.5f;
                        cs.moveTo((float) p.getX() - s, (float) p.getY());
                        cs.lineTo((float) p.getX() + s, (float) p.getY());
                        cs.moveTo((float) p.getX(), (float) p.getY() - s);
                        cs.lineTo((float) p.getX(), (float) p.getY() + s);
                        cs.stroke();
                    }
                };
            }
            case "CIRCLE": {
                final double cx = x1, cy = y1, radius = r;
                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        return new Rectangle2D.Double(cx - radius, cy - radius, radius * 2, radius * 2);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(finalStrokeWidth);
                        Path2D.Double path = new Path2D.Double();
                        BulgeConverter.appendArcToBezier(path, cx, cy, radius, 0, 2 * Math.PI);
                        emitPath(cs, path, xform);
                        cs.stroke();
                    }
                };
            }
            case "ARC": {
                final double cx = x1, cy = y1, radius = r, sa = startA, ea = endA;
                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        return new Rectangle2D.Double(cx - radius, cy - radius, radius * 2, radius * 2);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(finalStrokeWidth);
                        double startRad = Math.toRadians(sa);
                        double endRad = Math.toRadians(ea);
                        double sweep = endRad - startRad;
                        if (sweep <= 0) sweep += 2 * Math.PI;

                        Path2D.Double path = new Path2D.Double();
                        BulgeConverter.appendArcToBezier(path, cx, cy, radius, startRad, sweep);
                        emitPath(cs, path, xform);
                        cs.stroke();
                    }
                };
            }
            case "LWPOLYLINE": {
                List<Point2D.Double> vertices = new ArrayList<>();
                List<Double> bulges = new ArrayList<>();
                boolean isClosed = false;
                for (int j = 0; j < groups.size(); j++) {
                    DxfTokenizer.Group g = groups.get(j);
                    if (g.code() == 70 && (g.asInt() & 1) != 0) isClosed = true;
                    if (g.code() == 10) {
                        double vx = g.asDouble();
                        double vy = 0.0;
                        if (j + 1 < groups.size() && groups.get(j + 1).code() == 20) {
                            vy = groups.get(j + 1).asDouble();
                        }
                        vertices.add(new Point2D.Double(vx, vy));
                        bulges.add(0.0);
                    }
                    if (g.code() == 42 && !bulges.isEmpty()) {
                        bulges.set(bulges.size() - 1, g.asDouble());
                    }
                }

                boolean geometricallyClosed = false;
                if (vertices.size() >= 3) {
                    Point2D.Double first = vertices.get(0);
                    Point2D.Double last = vertices.get(vertices.size() - 1);
                    if (first.distance(last) < 1e-3) {
                        geometricallyClosed = true;
                    }
                }
                final boolean closed = isClosed || geometricallyClosed;
                final List<Point2D.Double> pts = vertices;
                final List<Double> blgs = bulges;
                final boolean columnPoly = isColumn;
                final String fLayer = layerName;
                final Color polyHatchColor = (closed && hatchEngine != null && hatchEngine.isEnabled() && hatchEngine.shouldHatch(layerName))
                        ? hatchEngine.resolveHatchColor(layerName, resolvedAci)
                        : null;

                return new DxfShape() {
                    private Path2D.Double createPath() {
                        Path2D.Double p = new Path2D.Double();
                        if (pts.isEmpty()) return p;
                        p.moveTo(pts.get(0).x, pts.get(0).y);
                        for (int k = 0; k < pts.size() - 1; k++) {
                            BulgeConverter.appendBulge(p, pts.get(k), pts.get(k + 1), blgs.get(k));
                        }
                        if (closed && pts.size() > 1) {
                            BulgeConverter.appendBulge(p, pts.get(pts.size() - 1), pts.get(0), blgs.get(blgs.size() - 1));
                            p.closePath();
                        }
                        return p;
                    }
                    public Rectangle2D getBounds() { return createPath().getBounds2D(); }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Path2D.Double p = createPath();
                        if (polyHatchColor != null && closed) {
                            Path2D.Double pagePath = new Path2D.Double();
                            PathIterator it = p.getPathIterator(xform);
                            pagePath.append(it, false);
                            Rectangle2D pageBounds = pagePath.getBounds2D();
                            if (!pageBounds.isEmpty() && pageBounds.getWidth() > 1 && pageBounds.getHeight() > 1) {
                                double angle = 45.0;
                                String uLayer = fLayer.toUpperCase();
                                boolean isCross = uLayer.contains("PARKING");
                                if (uLayer.contains("COVERED_AREA") || uLayer.contains("FOOT_PRINT")) {
                                    angle = 135.0;
                                }
                                double paperSpacing = 3.5;
                                List<Line2D.Double> lines = HatchPatternGenerator.generatePatternLines(
                                        pageBounds, angle, 1.0, isCross, paperSpacing);
                                cs.saveGraphicsState();
                                emitPath(cs, p, xform);
                                cs.clipEvenOdd();
                                cs.setStrokingColor(polyHatchColor);
                                cs.setLineWidth(0.18f);
                                for (Line2D.Double line : lines) {
                                    cs.moveTo((float) line.x1, (float) line.y1);
                                    cs.lineTo((float) line.x2, (float) line.y2);
                                }
                                cs.stroke();
                                cs.restoreGraphicsState();
                            }
                        } else if (columnPoly && closed) {
                            cs.setNonStrokingColor(Color.BLACK);
                            emitPath(cs, p, xform);
                            cs.fillEvenOdd();
                        }
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(finalStrokeWidth);
                        emitPath(cs, p, xform);
                        cs.stroke();
                    }
                };
            }
            case "SOLID": {
                double sx0 = x1, sy0 = y1;
                double sx1 = 0, sy1 = 0, sx2 = 0, sy2 = 0, sx3 = 0, sy3 = 0;
                for (DxfTokenizer.Group g : groups) {
                    switch (g.code()) {
                        case 11: sx1 = g.asDouble(); break;
                        case 21: sy1 = g.asDouble(); break;
                        case 12: sx2 = g.asDouble(); break;
                        case 22: sy2 = g.asDouble(); break;
                        case 13: sx3 = g.asDouble(); break;
                        case 23: sy3 = g.asDouble(); break;
                    }
                }
                final Point2D.Double p0 = new Point2D.Double(sx0, sy0);
                final Point2D.Double p1 = new Point2D.Double(sx1, sy1);
                final Point2D.Double p2 = new Point2D.Double(sx2, sy2);
                final Point2D.Double p3 = new Point2D.Double(sx3, sy3);
                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        return DxfSolidHelper.createSolidPath(p0, p1, p2, p3).getBounds2D();
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Color fillColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setNonStrokingColor(fillColor);
                        emitPath(cs, DxfSolidHelper.createSolidPath(p0, p1, p2, p3), xform);
                        cs.fill();
                    }
                };
            }
            case "HATCH": {
                boolean isSolid = false;
                double hAngle = 0.0, hScale = 1.0;
                String patName = "";
                double patAngle = 45.0;
                boolean hasPatAngle = false;
                double offsetDx = 0.0, offsetDy = 0.0;
                boolean hasOffset = false;

                for (DxfTokenizer.Group g : groups) {
                    if (g.code() == 70 && g.asInt() == 1) isSolid = true;
                    if (g.code() == 2) patName = g.value();
                    if (g.code() == 52) hAngle = g.asDouble();
                    if (g.code() == 53) { patAngle = g.asDouble(); hasPatAngle = true; }
                    if (g.code() == 41) hScale = g.asDouble();
                    if (g.code() == 45) { offsetDx = g.asDouble(); hasOffset = true; }
                    if (g.code() == 46) { offsetDy = g.asDouble(); hasOffset = true; }
                }

                double effAngle = hAngle;
                if (hasPatAngle) {
                    effAngle = patAngle + hAngle;
                } else if ("ANSI31".equalsIgnoreCase(patName)) {
                    effAngle = 45.0 + hAngle;
                }

                double effSpacing = -1.0;
                if (hasOffset) {
                    double dist = Math.hypot(offsetDx, offsetDy);
                    if (dist > 0) effSpacing = dist * (hScale <= 0 ? 1.0 : hScale);
                }
                if (effSpacing <= 0 && "ANSI31".equalsIgnoreCase(patName)) {
                    effSpacing = 0.25 * (hScale <= 0 ? 1.0 : hScale);
                }

                final boolean solid = isSolid || isColumn;
                final double angle = effAngle, sc = hScale, spacing = effSpacing;
                final Path2D.Double hatchPath = parseHatchLoops(groups);
                if (hatchPath.getPathIterator(null).isDone()) return null;
                if (hatchEngine != null && !hatchEngine.isEnabled()) {
                    return null; // Hatch rendering disabled by default unless explicitly selected
                }
                final String fHatchLayer = layerName;
                final Color bgHatchColor = (hatchEngine != null && hatchEngine.shouldHatch(layerName))
                        ? hatchEngine.resolveHatchColor(layerName, resolvedAci)
                        : null;

                return new DxfShape() {
                    public Rectangle2D getBounds() { return hatchPath.getBounds2D(); }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        if (solid) {
                            if (bgHatchColor != null && !isColumn) {
                                Path2D.Double pagePath = new Path2D.Double();
                                PathIterator it = hatchPath.getPathIterator(xform);
                                pagePath.append(it, false);
                                Rectangle2D pageBounds = pagePath.getBounds2D();
                                if (!pageBounds.isEmpty() && pageBounds.getWidth() > 1 && pageBounds.getHeight() > 1) {
                                    double hAngle = 45.0;
                                    String uLayer = fHatchLayer.toUpperCase();
                                    boolean isCross = uLayer.contains("PARKING");
                                    if (uLayer.contains("COVERED_AREA") || uLayer.contains("FOOT_PRINT")) {
                                        hAngle = 135.0;
                                    }
                                    double paperSpacing = 3.5;
                                    List<Line2D.Double> lines = HatchPatternGenerator.generatePatternLines(
                                            pageBounds, hAngle, 1.0, isCross, paperSpacing);
                                    cs.saveGraphicsState();
                                    emitPath(cs, hatchPath, xform);
                                    cs.clipEvenOdd();
                                    cs.setStrokingColor(bgHatchColor);
                                    cs.setLineWidth(0.18f);
                                    for (Line2D.Double line : lines) {
                                        cs.moveTo((float) line.x1, (float) line.y1);
                                        cs.lineTo((float) line.x2, (float) line.y2);
                                    }
                                    cs.stroke();
                                    cs.restoreGraphicsState();
                                }
                            } else {
                                Color fillColor = (bgHatchColor != null) ? bgHatchColor : ((isByBlock && inheritedColor != null) ? inheritedColor : finalColor);
                                cs.setNonStrokingColor(fillColor);
                                emitPath(cs, hatchPath, xform);
                                cs.fillEvenOdd();
                            }
                        } else {
                            List<Line2D.Double> lines = HatchPatternGenerator.generatePatternLines(hatchPath.getBounds2D(), angle, sc, false, spacing);
                            cs.saveGraphicsState();
                            emitPath(cs, hatchPath, xform);
                            cs.clipEvenOdd();
                            Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                            cs.setStrokingColor(strokeColor);
                            cs.setLineWidth(0.20f);
                            int segCount = 0;
                            for (Line2D.Double line : lines) {
                                Point2D p1 = xform.transform(line.getP1(), null);
                                Point2D p2 = xform.transform(line.getP2(), null);
                                cs.moveTo((float) p1.getX(), (float) p1.getY());
                                cs.lineTo((float) p2.getX(), (float) p2.getY());
                                segCount++;
                                if (segCount >= 1000) {
                                    cs.stroke();
                                    segCount = 0;
                                }
                            }
                            if (segCount > 0) {
                                cs.stroke();
                            }
                            cs.restoreGraphicsState();
                        }
                    }
                };
            }
            case "ELLIPSE": {
                final double cx = x1, cy = y1, mdx = majorDx, mdy = majorDy, ratio = minorRatio;
                final double startP = startA, endP = (endA == 360 ? 2 * Math.PI : endA);
                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        return EllipseConverter.createEllipsePath(cx, cy, mdx, mdy, ratio, startP, endP).getBounds2D();
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        Color strokeColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;
                        cs.setStrokingColor(strokeColor);
                        cs.setLineWidth(finalStrokeWidth);
                        Path2D.Double path = EllipseConverter.createEllipsePath(cx, cy, mdx, mdy, ratio, startP, endP);
                        emitPath(cs, path, xform);
                        cs.stroke();
                    }
                };
            }
            case "TEXT":
            case "MTEXT": {
                final String text = DxfTextProcessor.cleanMText(textVal.toString());
                if (text.isEmpty()) return null;

                final double h = Math.max(0.01, textHeight);
                final double rot = rotationDeg;
                final String[] lines = text.split("\n");
                final double rad = Math.toRadians(rot);
                final double cos = Math.cos(rad);
                final double sin = Math.sin(rad);
                final int textHAlign = hAlign;
                final int textVAlign = vAlign;
                final int textAttachment = attachment;

                Point2D.Double initialAnchor;
                if ("TEXT".equals(type)) {
                    Point2D.Double p10 = new Point2D.Double(x1, y1);
                    Point2D.Double p11 = hasP11 ? new Point2D.Double(x2, y2) : p10;
                    initialAnchor = TextAlignmentHelper.getEffectiveAnchor(p10, p11, textHAlign, textVAlign);
                } else {
                    initialAnchor = new Point2D.Double(x1, y1);
                }
                final Point2D.Double anchor = initialAnchor;

                return new DxfShape() {
                    public Rectangle2D getBounds() {
                        double maxW = 0.0;
                        for (String l : lines) maxW = Math.max(maxW, l.length() * h * 0.55);
                        double totalH = lines.length * h * 1.2;
                        Rectangle2D.Double rect = new Rectangle2D.Double(anchor.x, anchor.y, maxW, totalH);
                        if (Math.abs(rot) > 1e-4) {
                            AffineTransform r = AffineTransform.getRotateInstance(rad, anchor.x, anchor.y);
                            return r.createTransformedShape(rect).getBounds2D();
                        }
                        return rect;
                    }

                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }

                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        PDType1Font font = PDType1Font.HELVETICA;
                        Color textColor = (isByBlock && inheritedColor != null) ? inheritedColor : finalColor;

                        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
                            String cleanLine = lines[lineIdx].replaceAll("[^\\x20-\\x7E]", "?");
                            if (cleanLine.isEmpty()) continue;

                            double lineWidth;
                            try {
                                lineWidth = (font.getStringWidth(cleanLine) / 1000.0) * h;
                            } catch (Exception e) {
                                lineWidth = cleanLine.length() * h * 0.55;
                            }
                            if (lineWidth <= 0) lineWidth = cleanLine.length() * h * 0.55;

                            Point2D.Double offset;
                            if ("TEXT".equals(type)) {
                                offset = TextAlignmentHelper.calculateTextOffset(textHAlign, textVAlign, lineWidth, h);
                            } else {
                                offset = TextAlignmentHelper.calculateMTextBaselineOffset(
                                        textAttachment, lineWidth, h, lines.length, lineIdx);
                            }

                            // Convert local offset (along rot and rot+90) to world coordinates
                            double worldX = anchor.x + offset.x * cos - offset.y * sin;
                            double worldY = anchor.y + offset.x * sin + offset.y * cos;

                            AffineTransform textMatrix = new AffineTransform(xform);
                            textMatrix.translate(worldX, worldY);
                            textMatrix.rotate(rad);

                            double ptX = textMatrix.getTranslateX();
                            double ptY = textMatrix.getTranslateY();
                            double effectiveScale = Math.hypot(textMatrix.getScaleX(), textMatrix.getShearY());
                            double totalRot = Math.atan2(textMatrix.getShearY(), textMatrix.getScaleX());

                            float ptSize = (float) (h * effectiveScale);
                            if (ptSize < 0.5f) ptSize = 0.5f;

                            try {
                                cs.beginText();
                                cs.setFont(font, ptSize);
                                cs.setNonStrokingColor(textColor);
                                cs.setTextMatrix(new org.apache.pdfbox.util.Matrix(
                                    (float) Math.cos(totalRot), (float) Math.sin(totalRot),
                                    (float) -Math.sin(totalRot), (float) Math.cos(totalRot),
                                    (float) ptX, (float) ptY
                                ));
                                cs.showText(cleanLine);
                                cs.endText();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                };
            }
            case "INSERT":
            case "DIMENSION": {
                String targetBlockName = blockRefName;
                boolean isDimEntity = "DIMENSION".equals(type);
                if (isDimEntity) {
                    for (DxfTokenizer.Group dg : groups) {
                        if (dg.code() == 2) targetBlockName = dg.value();
                    }
                }

                BlockDef block = blocks.get(targetBlockName.toUpperCase());
                if (block == null || block.shapes().isEmpty()) {
                    return null;
                }

                final double insX = x1, insY = y1;
                final double scaleX = (sx != 0 ? sx : 1.0);
                final double scaleY = (sy != 0 ? sy : 1.0);
                final double rotDeg = rotationDeg;
                final Point2D.Double basePt = block.basePoint();
                final List<DxfShape> childShapes = block.shapes();
                final boolean dimensionEntity = isDim;
                final int blockColorAci = colorAci;

                return new DxfShape() {
                    private AffineTransform getLocalMatrix() {
                        if (dimensionEntity) {
                            // Dimension anonymous blocks (*D...) are already generated in WCS coordinates
                            return new AffineTransform();
                        }
                        AffineTransform local = new AffineTransform();
                        local.translate(insX, insY);
                        local.rotate(Math.toRadians(rotDeg));
                        local.scale(scaleX, scaleY);
                        local.translate(-basePt.x, -basePt.y);
                        return local;
                    }
                    public Rectangle2D getBounds() {
                        AffineTransform local = getLocalMatrix();
                        Rectangle2D bounds = null;
                        for (DxfShape cs : childShapes) {
                            Rectangle2D b = cs.getBounds();
                            if (b != null && !b.isEmpty()) {
                                Path2D.Double p = new Path2D.Double(b);
                                Rectangle2D tb = p.createTransformedShape(local).getBounds2D();
                                if (bounds == null) bounds = tb;
                                else bounds.add(tb);
                            }
                        }
                        return bounds;
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale) throws IOException {
                        render(cs, xform, scale, null);
                    }
                    public void render(PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        render(null, cs, xform, scale, inheritedColor);
                    }
                    @Override
                    public void render(PDDocument document, PDPageContentStream cs, AffineTransform xform, double scale, Color inheritedColor) throws IOException {
                        AffineTransform combined = new AffineTransform(xform);
                        combined.concatenate(getLocalMatrix());
                        Color blockColor = dimensionEntity ? finalColor : (blockColorAci != 256 && blockColorAci != 0 ? finalColor : inheritedColor);
                        for (DxfShape child : childShapes) {
                            child.render(document, cs, combined, scale, blockColor);
                        }
                    }
                };
            }
            case "OLE2FRAME": {
                List<String> hexChunks = new ArrayList<>();
                for (DxfTokenizer.Group g : groups) {
                    if (g.code() == 310) {
                        hexChunks.add(g.value());
                    }
                }
                byte[] oleBytes = DxfImageHelper.parseHexData(hexChunks);
                BufferedImage img = DxfImageHelper.extractImageFromOleData(oleBytes);
                if (img == null) {
                    return null;
                }
                Point2D.Double p10 = new Point2D.Double(x1, y1);
                Point2D.Double p11 = hasP11 ? new Point2D.Double(x2, y2) : p10;
                AffineTransform localMatrix = DxfImageHelper.calculateOleTransform(p10, p11);
                return new DxfRasterImageShape(img, localMatrix);
            }
            case "IMAGE": {
                String imagedefHandle = "";
                double vx = 0, vy = 0;
                double pixW = 0, pixH = 0;
                for (DxfTokenizer.Group g : groups) {
                    if (g.code() == 340) imagedefHandle = g.value().toUpperCase();
                    if (g.code() == 12) vx = g.asDouble();
                    if (g.code() == 22) vy = g.asDouble();
                    if (g.code() == 13) pixW = g.asDouble();
                    if (g.code() == 23) pixH = g.asDouble();
                }
                ImageDef def = (imageDefs != null) ? imageDefs.get(imagedefHandle) : null;
                BufferedImage img = null;
                if (def != null) {
                    img = loadImageFile(def.filePath(), baseDir);
                }
                if (img == null) {
                    return null;
                }
                if (pixW <= 0) pixW = img.getWidth();
                if (pixH <= 0) pixH = img.getHeight();

                Point2D.Double origin = new Point2D.Double(x1, y1);
                Point2D.Double uVec = new Point2D.Double(x2, y2);
                Point2D.Double vVec = new Point2D.Double(vx, vy);
                AffineTransform localMatrix = DxfImageHelper.calculateImageTransform(origin, uVec, vVec, pixW, pixH);
                return new DxfRasterImageShape(img, localMatrix);
            }
        }
        return null;
    }

    public static void emitPath(PDPageContentStream cs, Path2D path, AffineTransform xform) throws IOException {
        PathIterator it = path.getPathIterator(xform);
        float[] coords = new float[6];
        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO: cs.moveTo(coords[0], coords[1]); break;
                case PathIterator.SEG_LINETO: cs.lineTo(coords[0], coords[1]); break;
                case PathIterator.SEG_CUBICTO: cs.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]); break;
                case PathIterator.SEG_QUADTO: cs.curveTo(coords[0], coords[1], coords[0], coords[1], coords[2], coords[3]); break;
                case PathIterator.SEG_CLOSE: cs.closePath(); break;
            }
            it.next();
        }
    }

    public static void renderPdfToPng(File pdfFile, File pngFile, int dpi) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                java.awt.image.BufferedImage img = renderer.renderImageWithDPI(p, dpi);
                File out = (p == 0) ? pngFile : new File(pngFile.getParentFile(),
                        pngFile.getName().replaceAll("(?i)\\.png$", "") + "_page" + (p + 1) + ".png");
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                javax.imageio.ImageIO.write(img, "PNG", out);
            }
        }
    }

    public static void main(String[] args) {
        // =====================================================================
        // CONFIGURE YOUR DEFAULT FILE PATHS HERE (FOR IDE "RUN" BUTTON)
        // You can change 'defaultInputDxf' to any DXF file you want to convert.
        // =====================================================================
        String defaultInputDxf  = "C:\\Users\\johnson.hedo\\Downloads\\FINAL with image.dxf";
        String defaultOutputPdf = "FINAL with image.pdf";
        String defaultOutputPng = "FINAL with image_preview.png"; // Set to null to skip PNG rendering
        // =====================================================================

        String inputPath = (args.length > 0 && !args[0].trim().isEmpty()) ? args[0] : defaultInputDxf;
        String outputPath = (args.length > 1 && !args[1].trim().isEmpty()) ? args[1] : defaultOutputPdf;
        String pngPath = (args.length > 2) ? args[2] : defaultOutputPng;

        try {
            File input = new File(inputPath);
            if (!input.exists()) {
                if ("C:\\Users\\johnson.hedo\\Downloads\\OLE1.dxf".equalsIgnoreCase(inputPath)) {
                    File alt = new File("C:\\Users\\johnson.hedo\\Downloads\\FOR IMAGE TEST.dxf");
                    if (alt.exists()) {
                        input = alt;
                    }
                }
            }
            if (input.exists() && input.getName().equalsIgnoreCase("FOR IMAGE TEST.dxf")) {
                File ole1 = new File(input.getParentFile(), "OLE1.dxf");
                if (ole1.exists()) {
                    System.out.println("Notice: 'FOR IMAGE TEST.dxf' has an unpopulated OLE frame. Converting 'OLE1.dxf' which contains the full embedded screenshot (" + ole1.length() + " bytes).");
                    input = ole1;
                }
            }
            if (!input.exists()) {
                System.err.println("Error: Input file does not exist: " + input.getAbsolutePath());
                System.out.println("Please update 'defaultInputDxf' in DxfToPdfConverter.java or pass as a command line argument.");
                return;
            }

            File output = new File(outputPath);
            System.out.println("Converting: " + input.getAbsolutePath() + " -> " + output.getAbsolutePath());
            try {
                convertFile(input, output);
            } catch (IOException ioe) {
                if (ioe.getMessage() != null && ioe.getMessage().contains("used by another process")) {
                    String altName = outputPath.replaceAll("(?i)\\.pdf$", "") + "_new.pdf";
                    output = new File(altName);
                    System.out.println("Notice: " + outputPath + " is locked by another program. Saving to: " + output.getAbsolutePath());
                    convertFile(input, output);
                } else {
                    throw ioe;
                }
            }
            System.out.println("Conversion complete! Output PDF size: " + output.length() + " bytes");
            System.out.println("Saved PDF to: " + output.getAbsolutePath());

            if (pngPath != null && !pngPath.trim().isEmpty()) {
                File pngOut = new File(pngPath);
                try {
                    renderPdfToPng(output, pngOut, 150);
                    System.out.println("Rendered preview PNG: " + pngOut.getAbsolutePath() + " (" + pngOut.length() + " bytes)");
                } catch (IOException ioe) {
                    File altPng = new File(pngPath.replaceAll("(?i)\\.png$", "") + "_new.png");
                    renderPdfToPng(output, altPng, 150);
                    System.out.println("Rendered preview PNG: " + altPng.getAbsolutePath() + " (" + altPng.length() + " bytes)");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
