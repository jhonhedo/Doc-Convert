package com.converter.dxf.test;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Automates black-box comparison between your converter's PDF and a commercial converter's PDF.
 */
public final class DxfBenchmarkComparison {

    public static final class BenchmarkReport {
        public final double diffPercentage;
        public final int mismatchedPixels;
        public final int totalPixels;
        public final File diffMaskFile;
        public final File overlayFile;

        public BenchmarkReport(
            double diffPercentage,
            int mismatchedPixels,
            int totalPixels,
            File diffMaskFile,
            File overlayFile
        ) {
            this.diffPercentage = diffPercentage;
            this.mismatchedPixels = mismatchedPixels;
            this.totalPixels = totalPixels;
            this.diffMaskFile = diffMaskFile;
            this.overlayFile = overlayFile;
        }

        public double diffPercentage() { return diffPercentage; }
        public int mismatchedPixels() { return mismatchedPixels; }
        public int totalPixels() { return totalPixels; }
        public File diffMaskFile() { return diffMaskFile; }
        public File overlayFile() { return overlayFile; }
    }

    private DxfBenchmarkComparison() {}

    /**
     * Compares two PDF files at specified DPI, auto-crops margins, and generates visual diff artifacts.
     */
    public static BenchmarkReport comparePdfs(
            File ourPdfFile,
            File commercialPdfFile,
            int pageIndex,
            int dpi,
            File outputDir) throws IOException {

        outputDir.mkdirs();

        // 1. Render both PDFs to 300 DPI images
        BufferedImage ourImg;
        BufferedImage refImg;

        try (PDDocument ourDoc = PDDocument.load(ourPdfFile)) {
            ourImg = new PDFRenderer(ourDoc).renderImageWithDPI(pageIndex, dpi);
        }
        try (PDDocument refDoc = PDDocument.load(commercialPdfFile)) {
            refImg = new PDFRenderer(refDoc).renderImageWithDPI(pageIndex, dpi);
        }

        // 2. Auto-crop to content bounding boxes to eliminate page margin differences
        BufferedImage ourCropped = cropToContent(ourImg);
        BufferedImage refCropped = cropToContent(refImg);

        // Normalize both cropped images to the same canvas size
        int width = Math.max(ourCropped.getWidth(), refCropped.getWidth());
        int height = Math.max(ourCropped.getHeight(), refCropped.getHeight());

        BufferedImage ourNorm = scaleImage(ourCropped, width, height);
        BufferedImage refNorm = scaleImage(refCropped, width, height);

        // 3. Generate Red-Highlight Diff Mask
        VisualRegressionComparator.DiffResult diffResult =
            VisualRegressionComparator.compare(ourNorm, refNorm, 12);

        File diffMaskFile = new File(outputDir, "benchmark_diff_mask.png");
        ImageIO.write(diffResult.diffImage(), "PNG", diffMaskFile);

        // 4. Generate Cyan-Magenta False Color Overlay
        BufferedImage overlayImg = createAnaglyphOverlay(ourNorm, refNorm);
        File overlayFile = new File(outputDir, "benchmark_overlay.png");
        ImageIO.write(overlayImg, "PNG", overlayFile);

        return new BenchmarkReport(
            diffResult.diffPercentage(),
            diffResult.mismatchedPixels(),
            diffResult.totalPixels(),
            diffMaskFile,
            overlayFile
        );
    }

    private static BufferedImage cropToContent(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = w, minY = h, maxX = 0, maxY = 0;
        int whiteThreshold = 250;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (minX > maxX || minY > maxY) {
            return img; // Completely blank image
        }

        int pad = 10;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);

        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static BufferedImage scaleImage(BufferedImage src, int targetW, int targetH) {
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage createAnaglyphOverlay(BufferedImage ourImg, BufferedImage refImg) {
        int w = ourImg.getWidth();
        int h = ourImg.getHeight();
        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgbOur = ourImg.getRGB(x, y);
                int rgbRef = refImg.getRGB(x, y);

                // Convert to luminance (0 = black/ink, 255 = white background)
                int lumOur = 255 - (((rgbOur >> 16) & 0xFF) + ((rgbOur >> 8) & 0xFF) + (rgbOur & 0xFF)) / 3;
                int lumRef = 255 - (((rgbRef >> 16) & 0xFF) + ((rgbRef >> 8) & 0xFF) + (rgbRef & 0xFF)) / 3;

                // Our strokes -> Red channel; Reference strokes -> Cyan (Green + Blue)
                int r = 255 - lumOur;
                int g = 255 - lumRef;
                int b = 255 - lumRef;

                overlay.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return overlay;
    }
}
