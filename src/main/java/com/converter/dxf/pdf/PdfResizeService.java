package com.converter.dxf.pdf;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * In-memory PDF Resizing and Rescaling Service.
 * Fits drawing sets to standard CAD sheets (A4, A3, A2, A1, A0) or custom dimensions.
 * Retains 100% mathematical vector geometry and embedded fonts with zero rasterization.
 */
public class PdfResizeService {

    public enum PaperSize {
        A0(2383.94f, 3370.39f, "A0 (841 x 1189 mm)"),
        A1(1683.78f, 2383.94f, "A1 (594 x 841 mm)"),
        A2(1190.55f, 1683.78f, "A2 (420 x 594 mm)"),
        A3(841.89f, 1190.55f, "A3 (297 x 420 mm)"),
        A4(595.28f, 841.89f, "A4 (210 x 297 mm)"),
        CUSTOM(0, 0, "Custom Dimensions");

        private final float shortEdgePt;
        private final float longEdgePt;
        private final String description;

        PaperSize(float shortEdgePt, float longEdgePt, String description) {
            this.shortEdgePt = shortEdgePt;
            this.longEdgePt = longEdgePt;
            this.description = description;
        }

        public float getShortEdgePt() {
            return shortEdgePt;
        }

        public float getLongEdgePt() {
            return longEdgePt;
        }

        public String getDescription() {
            return description;
        }

        public static PaperSize fromString(String val) {
            if (val == null || val.trim().isEmpty()) return A3;
            String lower = val.trim().toLowerCase();
            if (lower.contains("a0")) return A0;
            if (lower.contains("a1")) return A1;
            if (lower.contains("a2")) return A2;
            if (lower.contains("a3")) return A3;
            if (lower.contains("a4")) return A4;
            if (lower.contains("custom")) return CUSTOM;
            return A3;
        }
    }

    public enum ScaleMode {
        FIT,        // Proportional fit with margins, centered
        FILL,       // Stretch to fill exact sheet edges
        KEEP_SCALE  // 1:1 scale, centered on target sheet
    }

    public enum Orientation {
        AUTO,
        PORTRAIT,
        LANDSCAPE
    }

    public static class ResizeResult {
        private final byte[] pdfBytes;
        private final String filename;
        private final int totalPages;
        private final float targetWidthPt;
        private final float targetHeightPt;

        public ResizeResult(byte[] pdfBytes, String filename, int totalPages, float targetWidthPt, float targetHeightPt) {
            this.pdfBytes = pdfBytes;
            this.filename = filename;
            this.totalPages = totalPages;
            this.targetWidthPt = targetWidthPt;
            this.targetHeightPt = targetHeightPt;
        }

        public byte[] getPdfBytes() {
            return pdfBytes;
        }

        public String getFilename() {
            return filename;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public float getTargetWidthPt() {
            return targetWidthPt;
        }

        public float getTargetHeightPt() {
            return targetHeightPt;
        }
    }

    /**
     * Resizes pages of a PDF document to target paper size in volatile memory.
     */
    public static ResizeResult resize(byte[] pdfBytes, PaperSize targetSize, float customWidthPt, float customHeightPt,
                                      ScaleMode scaleMode, Orientation orientation, String pageRangeExpr, String sourceFilename) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF document content is empty.");
        }

        if (!isValidPdfHeader(pdfBytes)) {
            throw new IOException("The provided file is not a valid PDF document (missing %PDF- header).");
        }

        if (targetSize == null) targetSize = PaperSize.A3;
        if (scaleMode == null) scaleMode = ScaleMode.FIT;
        if (orientation == null) orientation = Orientation.AUTO;

        float baseWidthPt;
        float baseHeightPt;
        if (targetSize == PaperSize.CUSTOM) {
            if (customWidthPt <= 10 || customHeightPt <= 10) {
                throw new IllegalArgumentException("Custom dimensions must be greater than 10 pt.");
            }
            baseWidthPt = customWidthPt;
            baseHeightPt = customHeightPt;
        } else {
            baseWidthPt = targetSize.getShortEdgePt();
            baseHeightPt = targetSize.getLongEdgePt();
        }

        String baseName = sanitizeBaseName(sourceFilename);

        try (PDDocument sourceDoc = PDDocument.load(new ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupMainMemoryOnly());
             PDDocument targetDoc = new PDDocument()) {

            int totalPages = sourceDoc.getNumberOfPages();
            if (totalPages == 0) {
                throw new IOException("PDF document contains no pages.");
            }

            List<Integer> pagesToResize = PdfSplitService.parsePageRange(pageRangeExpr, totalPages);
            Set<Integer> resizeSet = new HashSet<>(pagesToResize);
            LayerUtility layerUtility = new LayerUtility(targetDoc);

            float lastTargetW = baseWidthPt;
            float lastTargetH = baseHeightPt;

            for (int i = 0; i < totalPages; i++) {
                PDPage srcPage = sourceDoc.getPage(i);
                int pageNum = i + 1;

                if (!resizeSet.contains(pageNum)) {
                    // Page not selected for resize: copy unmodified
                    targetDoc.importPage(srcPage);
                    continue;
                }

                PDRectangle srcBox = srcPage.getCropBox() != null ? srcPage.getCropBox() : srcPage.getMediaBox();
                float origW = srcBox.getWidth();
                float origH = srcBox.getHeight();

                // Determine target dimensions based on orientation
                float targetW;
                float targetH;
                if (targetSize == PaperSize.CUSTOM && orientation == Orientation.AUTO) {
                    targetW = baseWidthPt;
                    targetH = baseHeightPt;
                } else {
                    boolean isLandscape;
                    if (orientation == Orientation.LANDSCAPE) {
                        isLandscape = true;
                    } else if (orientation == Orientation.PORTRAIT) {
                        isLandscape = false;
                    } else {
                        // AUTO: match source orientation
                        isLandscape = (origW >= origH);
                    }
                    float shortEdge = Math.min(baseWidthPt, baseHeightPt);
                    float longEdge = Math.max(baseWidthPt, baseHeightPt);
                    targetW = isLandscape ? longEdge : shortEdge;
                    targetH = isLandscape ? shortEdge : longEdge;
                }

                lastTargetW = targetW;
                lastTargetH = targetH;

                PDPage newPage = new PDPage(new PDRectangle(targetW, targetH));
                targetDoc.addPage(newPage);

                // Import original page as vector Form XObject
                PDFormXObject form = layerUtility.importPageAsForm(sourceDoc, i);

                // Calculate affine transform
                AffineTransform xform = new AffineTransform();

                if (scaleMode == ScaleMode.KEEP_SCALE) {
                    // 1:1 scale, centered
                    double tx = (targetW - origW) / 2.0;
                    double ty = (targetH - origH) / 2.0;
                    xform.translate(tx, ty);
                } else if (scaleMode == ScaleMode.FILL) {
                    // Non-uniform stretch to fill exact target edges
                    double sx = targetW / origW;
                    double sy = targetH / origH;
                    xform.scale(sx, sy);
                } else {
                    // ScaleMode.FIT (Default): Proportional scale, centered
                    double scaleX = targetW / origW;
                    double scaleY = targetH / origH;
                    double scale = Math.min(scaleX, scaleY);
                    double scaledW = origW * scale;
                    double scaledH = origH * scale;
                    double tx = (targetW - scaledW) / 2.0;
                    double ty = (targetH - scaledH) / 2.0;

                    xform.translate(tx, ty);
                    xform.scale(scale, scale);
                }

                // Render vector Form XObject to the new page content stream
                try (PDPageContentStream cs = new PDPageContentStream(targetDoc, newPage, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                    cs.saveGraphicsState();
                    cs.transform(new Matrix(xform));
                    cs.drawForm(form);
                    cs.restoreGraphicsState();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            targetDoc.save(baos);
            byte[] outBytes = baos.toByteArray();
            String outName = String.format("%s_resized_%s.pdf", baseName, targetSize.name().toLowerCase());

            return new ResizeResult(outBytes, outName, totalPages, lastTargetW, lastTargetH);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to resize PDF document: " + e.getMessage(), e);
        }
    }

    private static boolean isValidPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        return header.contains("%PDF-");
    }

    private static String sanitizeBaseName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "document";
        String clean = filename.trim().replaceAll("(?i)\\.pdf$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isEmpty() ? "document" : clean;
    }
}

