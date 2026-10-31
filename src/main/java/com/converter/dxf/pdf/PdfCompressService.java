package com.converter.dxf.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * In-memory PDF compression and optimization service.
 * Performs in-place raster image downsampling and JPEG re-encoding,
 * compresses uncompressed vector content streams, removes redundant metadata,
 * and eliminates orphan XObjects with zero disk retention.
 */
public class PdfCompressService {

    public enum CompressionLevel {
        LOW(1400, 0.70f, "Mild compression (max image fidelity)"),
        MEDIUM(900, 0.50f, "Recommended (balanced DPI & size reduction)"),
        HIGH(600, 0.30f, "Extreme compression (maximum byte savings)");

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
            if (originalSize > 0 && compressedSize < originalSize) {
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

        if (!isValidPdfHeader(originalPdfBytes)) {
            throw new IOException("The provided file is not a valid PDF document (missing %PDF- header).");
        }

        long originalSize = originalPdfBytes.length;
        int optimizedImages = 0;
        int pageCount;

        PDDocument document;
        try {
            document = PDDocument.load(new ByteArrayInputStream(originalPdfBytes), MemoryUsageSetting.setupMainMemoryOnly());
        } catch (Exception e) {
            throw new IOException("Unable to parse PDF document: " + e.getMessage(), e);
        }

        try {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("PDF document contains no pages.");
            }
            pageCount = document.getNumberOfPages();

            // 1. Strip unneeded metadata streams (reduces overhead while preserving page rendering)
            if (document.getDocumentCatalog() != null) {
                document.getDocumentCatalog().setMetadata(null);
                document.getDocumentCatalog().getCOSObject().removeItem(COSName.PIECE_INFO);
            }

            // 2. Compress uncompressed page content streams
            compressPageContents(document);

            // 3. Iterate pages and optimize raster image XObjects in-place
            Set<COSBase> processedObjects = new HashSet<>();
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                if (resources != null) {
                    optimizedImages += optimizeResources(document, resources, level, processedObjects);
                }
            }

            // 4. Save to output stream
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            byte[] compressedBytes = out.toByteArray();

            // Guard: If compressed is larger than or equal to original, return original bytes with 0% savings
            if (compressedBytes.length >= originalSize) {
                return new CompressResult(originalPdfBytes, originalSize, originalSize, pageCount, 0);
            }

            return new CompressResult(compressedBytes, originalSize, compressedBytes.length, pageCount, optimizedImages);
        } finally {
            try {
                document.close();
            } catch (Exception ignored) {}
        }
    }

    private static boolean isValidPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), java.nio.charset.StandardCharsets.ISO_8859_1);
        return header.contains("%PDF-");
    }

    private static void compressPageContents(PDDocument document) {
        for (PDPage page : document.getPages()) {
            page.getCOSObject().removeItem(COSName.PIECE_INFO);

            COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            if (contents instanceof COSStream) {
                compressStreamIfUncompressed((COSStream) contents);
            } else if (contents instanceof COSArray) {
                for (COSBase item : (COSArray) contents) {
                    if (item instanceof COSObject) {
                        item = ((COSObject) item).getObject();
                    }
                    if (item instanceof COSStream) {
                        compressStreamIfUncompressed((COSStream) item);
                    }
                }
            }
        }
    }

    private static void compressStreamIfUncompressed(COSStream stream) {
        if (stream == null) return;
        COSBase filter = stream.getItem(COSName.FILTER);
        if (filter == null) {
            try {
                byte[] rawBytes;
                try (InputStream is = stream.createRawInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
                    rawBytes = baos.toByteArray();
                }

                if (rawBytes.length > 64) {
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed, new Deflater(Deflater.BEST_COMPRESSION))) {
                        deflater.write(rawBytes);
                    }
                    byte[] deflatedBytes = compressed.toByteArray();
                    if (deflatedBytes.length < rawBytes.length) {
                        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
                        try (OutputStream os = stream.createRawOutputStream()) {
                            os.write(deflatedBytes);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static int optimizeResources(PDDocument document, PDResources resources, CompressionLevel level, Set<COSBase> visited) {
        if (resources == null) return 0;
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject == null) continue;
                COSStream cosStream = xobject.getCOSObject();
                if (cosStream == null || visited.contains(cosStream)) continue;
                visited.add(cosStream);

                if (xobject instanceof PDImageXObject) {
                    if (optimizeImageStream((PDImageXObject) xobject, level)) {
                        count++;
                    }
                } else if (xobject instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xobject;
                    compressStreamIfUncompressed(form.getCOSObject());
                    count += optimizeResources(document, form.getResources(), level, visited);
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    private static boolean optimizeImageStream(PDImageXObject img, CompressionLevel level) {
        try {
            COSStream cos = img.getCOSObject();
            if (cos == null) return false;

            BufferedImage rawImage = img.getImage();
            if (rawImage == null) return false;

            int origWidth = rawImage.getWidth();
            int origHeight = rawImage.getHeight();
            if (origWidth <= 0 || origHeight <= 0) return false;

            int maxDim = level.getMaxDimension();
            boolean needsRescaling = origWidth > maxDim || origHeight > maxDim;
            BufferedImage processedImage = needsRescaling ? rescaleImage(rawImage, maxDim) : rawImage;

            long originalStreamLength = getStreamLength(cos);

            // If image has real alpha/transparency, only rescale if dimensions exceed limit
            if (hasActualTransparency(processedImage)) {
                return false;
            }

            // Compress to JPEG with explicit quality
            byte[] jpegBytes = compressToJpeg(processedImage, level.getJpegQuality());
            if (jpegBytes == null || jpegBytes.length == 0) return false;

            // Only replace if new stream is smaller than original stream (or rescaled significantly)
            if (originalStreamLength > 0 && jpegBytes.length >= originalStreamLength && !needsRescaling) {
                return false;
            }

            // In-place replacement on existing COSStream: prevents orphan duplicate objects in COSDocument
            cos.setItem(COSName.FILTER, COSName.DCT_DECODE);
            cos.removeItem(COSName.DECODE_PARMS);
            cos.setInt(COSName.WIDTH, processedImage.getWidth());
            cos.setInt(COSName.HEIGHT, processedImage.getHeight());
            cos.setInt(COSName.BITS_PER_COMPONENT, 8);
            cos.setItem(COSName.COLORSPACE, COSName.DEVICERGB);

            try (OutputStream os = cos.createRawOutputStream()) {
                os.write(jpegBytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long getStreamLength(COSStream cos) {
        long len = cos.getInt(COSName.LENGTH);
        if (len > 0) return len;
        try (InputStream is = cos.createRawInputStream()) {
            long count = 0;
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) count += r;
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] compressToJpeg(BufferedImage image, float quality) throws IOException {
        BufferedImage rgbImage;
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            rgbImage = image;
        } else {
            int w = image.getWidth();
            int h = image.getHeight();
            rgbImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = rgbImage.createGraphics();
            try {
                g2.drawImage(image, 0, 0, Color.WHITE, null);
            } finally {
                g2.dispose();
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            writers = ImageIO.getImageWritersByFormatName("jpg");
        }
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG writer not available in runtime.");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            JPEGImageWriteParam params = new JPEGImageWriteParam(null);
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgbImage, null, null), params);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private static boolean hasActualTransparency(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int rgb : row) {
                int alpha = (rgb >> 24) & 0xFF;
                if (alpha < 250) {
                    return true;
                }
            }
        }
        return false;
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

        int imageType = original.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
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
}
