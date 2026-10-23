package com.converter.dxf.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;

public class InspectTest {

    @Test
    public void inspectPdf() throws Exception {
        String[] paths = {
            "d:\\dxfToPdf\\edcr_system_scrutinized.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\Passing DCR v6 1_system_scrutinized.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\DRAWINGS - METERS - UPLOAD_system_scrutinized.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\refrence openclaw.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\updated spec open claw.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\IMAGE DRAWING.dxf_java_.pdf",
            "C:\\Users\\johnson.hedo\\Downloads\\FOR IMAGE TEST.pdf",
            "d:\\dxfToPdf\\HOTEL  EDCR (1).pdf"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                try (PDDocument doc = PDDocument.load(f)) {
                    System.out.println("=== " + f.getName() + " (" + doc.getNumberOfPages() + " pages) ===");
                    PDFRenderer r = new PDFRenderer(doc);
                    for (int i = 0; i < doc.getNumberOfPages(); i++) {
                        org.apache.pdfbox.pdmodel.common.PDRectangle mb = doc.getPage(i).getMediaBox();
                        System.out.println("  Page " + (i + 1) + ": " + mb.getWidth() + " x " + mb.getHeight() + " pt (" + (mb.getWidth() * 25.4 / 72.0) + " x " + (mb.getHeight() * 25.4 / 72.0) + " mm)");
                        if (f.getName().contains("HOTEL")) {
                            try {
                                BufferedImage img = r.renderImageWithDPI(i, 20);
                                ImageIO.write(img, "PNG", new File("target/aspose_hotel_page_" + (i + 1) + ".png"));
                                System.out.println("  Saved target/aspose_hotel_page_" + (i + 1) + ".png");
                            } catch (Exception e) {
                                System.out.println("  Render error: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void analyzeHotelSizes() throws Exception {
        String[] files = {
            "d:\\dxfToPdf\\HOTEL  EDCR (1).pdf",
            "target\\hotel_edcr_converted.pdf"
        };
        for (String p : files) {
            File f = new File(p);
            if (!f.exists()) continue;
            System.out.println("========== " + f.getName() + " (Total file size: " + f.length() + " bytes) ==========");
            try (PDDocument doc = PDDocument.load(f)) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(i);
                    java.io.InputStream stream = page.getContents();
                    int len = 0;
                    if (stream != null) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = stream.read(buf)) != -1) len += r;
                    }
                    System.out.println("  Page " + (i + 1) + " uncompressed content stream size: " + len + " bytes (" + (len / 1024 / 1024) + " MB)");
                }
            }
        }
    }

    @Test
    public void inspectHatchDetails() throws Exception {
        File dxf = new File("C:\\Users\\johnson.hedo\\Downloads\\HOTEL  EDCR (1).dxf");
        if (!dxf.exists()) return;
        String content = new String(java.nio.file.Files.readAllBytes(dxf.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<com.converter.dxf.parser.DxfTokenizer.Group> groups = com.converter.dxf.parser.DxfTokenizer.tokenize(content);
        
        com.converter.dxf.render.hatch.DynamicHatchRuleEngine engine = com.converter.dxf.render.hatch.DynamicHatchRuleEngine.getDefault();
        java.util.Map<String, com.converter.dxf.DxfToPdfConverter.LayerInfo> layerTable = com.converter.dxf.DxfToPdfConverter.parseLayers(groups);
        java.util.Map<String, com.converter.dxf.DxfToPdfConverter.ImageDef> imageDefs = com.converter.dxf.DxfToPdfConverter.parseImageDefs(groups);
        java.util.Map<String, com.converter.dxf.DxfToPdfConverter.BlockDef> blockTable = com.converter.dxf.DxfToPdfConverter.parseBlocks(groups, layerTable, engine, imageDefs, null);
        java.util.List<com.converter.dxf.DxfToPdfConverter.DxfShape> shapes = com.converter.dxf.DxfToPdfConverter.parseEntitiesSection(groups, layerTable, blockTable, engine, imageDefs, null);

        System.out.println("Total shapes in model space: " + shapes.size());
        
        long totalHatchLines = 0;
        int hatchCount = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).code() == 0 && "HATCH".equalsIgnoreCase(groups.get(i).value())) {
                hatchCount++;
                int j = i + 1;
                double hScale = 1.0;
                String patName = "";
                double offsetDx = 0, offsetDy = 0;
                boolean hasOffset = false;
                while (j < groups.size() && groups.get(j).code() != 0) {
                    if (groups.get(j).code() == 41) hScale = groups.get(j).asDouble();
                    if (groups.get(j).code() == 2) patName = groups.get(j).value();
                    if (groups.get(j).code() == 45) { offsetDx = groups.get(j).asDouble(); hasOffset = true; }
                    if (groups.get(j).code() == 46) { offsetDy = groups.get(j).asDouble(); hasOffset = true; }
                    j++;
                }
                double effSpacing = -1.0;
                if (hasOffset) {
                    double dist = Math.hypot(offsetDx, offsetDy);
                    if (dist > 0) effSpacing = dist * (hScale <= 0 ? 1.0 : hScale);
                }
                if (effSpacing <= 0 && "ANSI31".equalsIgnoreCase(patName)) {
                    effSpacing = 0.25 * (hScale <= 0 ? 1.0 : hScale);
                }
                if (effSpacing <= 0) {
                    effSpacing = (hScale <= 0 ? 1.0 : hScale) * 3.0;
                }
                // Let's find bounds
                // parse loops
                java.util.List<com.converter.dxf.parser.DxfTokenizer.Group> hg = groups.subList(i, j);
                java.awt.geom.Path2D.Double p = com.converter.dxf.DxfToPdfConverter.parseHatchLoops(hg);
                java.awt.geom.Rectangle2D b = p.getBounds2D();
                if (!b.isEmpty()) {
                    double radius = Math.hypot(b.getWidth(), b.getHeight()) / 2.0;
                    long steps = (long) Math.ceil(radius / effSpacing);
                    long lineCount = steps * 2 + 1;
                    totalHatchLines += lineCount;
                    if (lineCount > 1000) {
                        System.out.println("Hatch " + hatchCount + " (" + patName + "): scale=" + hScale + ", effSpacing=" + effSpacing + ", w=" + b.getWidth() + ", h=" + b.getHeight() + ", steps=" + steps + " -> " + lineCount + " lines");
                    }
                }
            }
        }
        System.out.println("Total calculated hatch lines across all hatches: " + totalHatchLines);
    }

    @Test
    public void analyzeAsposeOperators() throws Exception {
        File f = new File("d:\\dxfToPdf\\HOTEL  EDCR (1).pdf");
        if (!f.exists()) return;
        try (PDDocument doc = PDDocument.load(f)) {
            org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(0);
            org.apache.pdfbox.pdfparser.PDFStreamParser parser = new org.apache.pdfbox.pdfparser.PDFStreamParser(page);
            parser.parse();
            java.util.List<Object> tokens = parser.getTokens();
            java.util.Map<String, Integer> opCounts = new java.util.HashMap<>();
            int totalTokens = tokens.size();
            for (Object token : tokens) {
                if (token instanceof org.apache.pdfbox.contentstream.operator.Operator) {
                    String op = ((org.apache.pdfbox.contentstream.operator.Operator) token).getName();
                    opCounts.put(op, opCounts.getOrDefault(op, 0) + 1);
                }
            }
            System.out.println("Total tokens on Aspose Page 1: " + totalTokens);
            opCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(20)
                    .forEach(e -> System.out.println("  Operator " + e.getKey() + ": " + e.getValue()));
        }
    }

    @Test
    public void testLargeDxfConversion() throws Exception {
        String[] candidates = {
            "C:\\Users\\johnson.hedo\\Downloads\\SUBMISSION DXF OF IIT KAMAND.dxf"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (!f.exists()) continue;
            System.out.println("Converting large DXF: " + f.getName() + " (" + f.length() + " bytes)");
            File out = new File("target/" + f.getName().replaceAll("(?i)\\.dxf$", ".pdf"));
            long t0 = System.currentTimeMillis();
            com.converter.dxf.DxfToPdfConverter.convertFile(f, out);
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("Converted " + f.getName() + " in " + elapsed + "ms -> " + out.length() + " bytes (" + (out.length() / 1024 / 1024) + " MB)");
            break; // Test one large file
        }
    }

    @Test
    public void renderTestPages() throws Exception {
        File edcrPdf = new File("d:\\dxfToPdf\\edcr_system_scrutinized.pdf");
        if (edcrPdf.exists()) {
            try (PDDocument doc = PDDocument.load(edcrPdf)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                for (int i = 0; i < Math.min(3, doc.getNumberOfPages()); i++) {
                    java.awt.image.BufferedImage img = r.renderImageWithDPI(i, 72);
                    javax.imageio.ImageIO.write(img, "PNG", new File("target/edcr_page_" + (i + 1) + ".png"));
                }
            }
        }
        File passingPdf = new File("C:\\Users\\johnson.hedo\\Downloads\\Passing DCR v6 1_system_scrutinized.pdf");
        if (passingPdf.exists()) {
            try (PDDocument doc = PDDocument.load(passingPdf)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                java.awt.image.BufferedImage img = r.renderImageWithDPI(0, 36);
                javax.imageio.ImageIO.write(img, "PNG", new File("target/passing_page_1.png"));
            }
        }
    }





    @Test
    public void testConvert22June() throws Exception {
        String[] files = {
            "C:\\Users\\johnson.hedo\\Downloads\\HOTEL  EDCR (1).dxf"
        };
        for (String filePath : files) {
            File dxf = new File(filePath);
            if (!dxf.exists()) continue;
            System.out.println("Processing: " + dxf.getName() + " (" + dxf.length() + " bytes)");
            File pdfOut = new File("target/hotel_edcr_converted.pdf");
            long t0 = System.currentTimeMillis();
            com.converter.dxf.DxfToPdfConverter.convertFile(dxf, pdfOut);
            System.out.println("Converted in " + (System.currentTimeMillis() - t0) + "ms");
            try (PDDocument doc = PDDocument.load(pdfOut)) {
                System.out.println("=== " + dxf.getName() + " CONVERTED (" + doc.getNumberOfPages() + " pages) ===");
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                for (int p = 0; p < doc.getNumberOfPages(); p++) {
                    org.apache.pdfbox.pdmodel.common.PDRectangle mb = doc.getPage(p).getMediaBox();
                    System.out.println("  Page " + (p + 1) + ": " + mb.getWidth() + " x " + mb.getHeight() + " pt");
                    try {
                        java.awt.image.BufferedImage img = r.renderImageWithDPI(p, 15);
                        javax.imageio.ImageIO.write(img, "PNG", new File("target/hotel_page_" + (p + 1) + ".png"));
                        System.out.println("  Rendered target/hotel_page_" + (p + 1) + ".png");
                    } catch (Exception ex) {
                        System.out.println("  Error rendering preview: " + ex.getMessage());
                    }
                }
            }
        }
    }
}
