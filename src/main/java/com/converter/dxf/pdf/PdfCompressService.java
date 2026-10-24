package com.converter.dxf.pdf;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * In-memory PDF compression and optimization service.
 * Downsamples embedded raster images, removes redundant metadata streams,
 * and reapplies Flate stream compression while preserving all vector geometry.
 */
public class PdfCompressService {

    public enum CompressionLevel {
        LOW(1600, 0.85f, "Mild compression (max image fidelity)"),
        MEDIUM(1200, 0.70f, "Recommended (balanced DPI & size reduction)"),
        HIGH(800, 0.50f, "Extreme compression (maximum byte savings)");

        private final int maxDimension;
        private final float jpegQuality;
        private final String description;

        CompressionLevel(int maxDimension, float jpegQuality, String description) {
            this.maxDimension = maxDimension;
            this.jpegQuality = jpegQuality;
            this.description = description;
        }

        public int getMaxDimension() {
            return maxDimension;
        }

        public float getJpegQuality() {
            return jpegQuality;
        }

        public String getDescription() {
            return description;
        }

        public static CompressionLevel fromString(String val) {
            if (val == null || val.trim().isEmpty()) return MEDIUM;
            String lower = val.trim().toLowerCase();
            if (lower.contains("low") || lower.contains("mild") || lower.contains("light")) return LOW;
            if (lower.contains("high") || lower.contains("extreme") || lower.contains("max")) return HIGH;
            return MEDIUM;
        }
    }

    public static class CompressResult {
        private final byte[] pdfBytes;
        private final long originalSize;
        private final long compressedSize;
        private final double savingsPercentage;
        private final int totalPages;
        private final int optimizedImagesCount;

        public CompressResult(byte[] pdfBytes, long originalSize, long compressedSize, int totalPages, int optimizedImagesCount) {
            this.pdfBytes = pdfBytes;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.totalPages = totalPages;
            this.optimizedImagesCount = optimizedImagesCount;
            if (originalSize > 0) {
                double diff = (double) (originalSize - compressedSize);
                this.savingsPercentage = Math.max(0.0, Math.min(99.9, (diff / originalSize) * 100.0));
            } else {
                this.savingsPercentage = 0.0;
            }
        }

        public byte[] getPdfBytes() {
            return pdfBytes;
        }

        public long getOriginalSize() {
            return originalSize;
        }

        public long getCompressedSize() {
            return compressedSize;
        }

        public double getSavingsPercentage() {
            return savingsPercentage;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public int getOptimizedImagesCount() {
            return optimizedImagesCount;
        }
    }

    /**
     * Compresses the provided PDF document in volatile memory.
     *
     * @param originalPdfBytes Raw bytes of the PDF to compress.
     * @param level            Target compression level.
     * @return CompressResult containing the optimized PDF bytes and reduction metrics.
     * @throws IOException if parsing or compression fails.
     */
    public static CompressResult compress(byte[] originalPdfBytes, CompressionLevel level) throws IOException {
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF content is empty.");
        }

        long originalSize = originalPdfBytes.length;
        int optimizedImages = 0;
        int pageCount;

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(originalPdfBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            pageCount = document.getNumberOfPages();

            // 1. Strip unneeded metadata streams (reduces overhead while preserving page rendering)
            if (document.getDocumentCatalog() != null) {
                document.getDocumentCatalog().setMetadata(null);
            }

            // 2. Iterate pages and optimize raster image XObjects
            Set<COSName> processedObjects = new HashSet<>();
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                if (resources != null) {
                    optimizedImages += optimizeResources(document, resources, level, processedObjects);
                }
            }

            // 3. Save to output stream using standard Flate stream compression
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            byte[] compressedBytes = out.toByteArray();

            // Guard: If compressed is somehow larger than original (e.g. already compressed high-efficiency PDF),
            // and no image optimization was applied, return original or the smaller one
            if (compressedBytes.length > originalSize && optimizedImages == 0) {
                return new CompressResult(originalPdfBytes, originalSize, originalSize, pageCount, 0);
            }

            return new CompressResult(compressedBytes, originalSize, compressedBytes.length, pageCount, optimizedImages);
        }
    }

    private static int optimizeResources(PDDocument document, PDResources resources, CompressionLevel level, Set<COSName> visited) {
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            if (visited.contains(name)) continue;
            visited.add(name);

            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDImageXObject) {
                    PDImageXObject img = (PDImageXObject) xobject;
                    int origWidth = img.getWidth();
                    int origHeight = img.getHeight();

                    // Only downsample if image dimension exceeds threshold or quality compression can reduce size
                    if (origWidth > level.getMaxDimension() || origHeight > level.getMaxDimension() || level == CompressionLevel.HIGH) {
                        BufferedImage rawImage = img.getImage();
                        if (rawImage != null) {
                            BufferedImage rescaled = rescaleImage(rawImage, level.getMaxDimension());
                            PDImageXObject optimizedXObject;

                            // Compress with JPEG if RGB, or Lossless if transparency/palette
                            if (hasAlpha(rescaled)) {
                                optimizedXObject = LosslessFactory.createFromImage(document, rescaled);
                            } else {
                                optimizedXObject = JPEGFactory.createFromImage(document, rescaled, level.getJpegQuality());
                            }

                            resources.put(name, optimizedXObject);
                            count++;
                        }
                    }
                } else if (xobject instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xobject;
                    PDResources childResources = form.getResources();
                    if (childResources != null) {
                        count += optimizeResources(document, childResources, level, visited);
                    }
                }
            } catch (Exception ignored) {
                // Ignore single image optimization error to guarantee document integrity
            }
        }
        return count;
    }

    private static BufferedImage rescaleImage(BufferedImage original, int maxDimension) {
        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }

        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));

        int imageType = (original.getType() == 0) ? BufferedImage.TYPE_INT_RGB : original.getType();
        BufferedImage target = new BufferedImage(targetW, targetH, imageType);

        Graphics2D g2 = target.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(original, 0, 0, targetW, targetH, null);
        } finally {
            g2.dispose();
        }

        return target;
    }

    private static boolean hasAlpha(BufferedImage img) {
        return img.getColorModel().hasAlpha();
    }
}
