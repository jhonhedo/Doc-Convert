package com.converter.dxf.test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Visual regression comparator: compares two rendered page images pixel-by-pixel,
 * computes a percentage difference score, and outputs a highlighted diff image.
 */
public final class VisualRegressionComparator {

    public static final class DiffResult {
        public final int totalPixels;
        public final int mismatchedPixels;
        public final double diffPercentage;
        public final BufferedImage diffImage;

        public DiffResult(
            int totalPixels,
            int mismatchedPixels,
            double diffPercentage,
            BufferedImage diffImage
        ) {
            this.totalPixels = totalPixels;
            this.mismatchedPixels = mismatchedPixels;
            this.diffPercentage = diffPercentage;
            this.diffImage = diffImage;
        }

        public int totalPixels() { return totalPixels; }
        public int mismatchedPixels() { return mismatchedPixels; }
        public double diffPercentage() { return diffPercentage; }
        public BufferedImage diffImage() { return diffImage; }

        public boolean isIdentical() {
            return mismatchedPixels == 0;
        }

        public boolean passesThreshold(double maxAllowedDiffPercent) {
            return diffPercentage <= maxAllowedDiffPercent;
        }
    }

    private VisualRegressionComparator() {}

    /**
     * Compares an actual image against a reference image.
     *
     * @param actual Actual rendered image
     * @param reference Reference baseline image
     * @param colorToleranceMax Per-channel color difference tolerance (0..255, typically 5..10 for anti-aliasing)
     * @return DiffResult containing pixel statistics and visual diff mask
     */
    public static DiffResult compare(BufferedImage actual, BufferedImage reference, int colorToleranceMax) {
        int width = Math.max(actual.getWidth(), reference.getWidth());
        int height = Math.max(actual.getHeight(), reference.getHeight());

        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int totalPixels = width * height;
        int mismatchedPixels = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= actual.getWidth() || y >= actual.getHeight() ||
                    x >= reference.getWidth() || y >= reference.getHeight()) {
                    // One image is out of bounds
                    diffImage.setRGB(x, y, Color.MAGENTA.getRGB());
                    mismatchedPixels++;
                    continue;
                }

                int rgbActual = actual.getRGB(x, y);
                int rgbRef = reference.getRGB(x, y);

                if (rgbActual == rgbRef) {
                    // Match: dim baseline to light gray
                    int gray = 245;
                    diffImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                    continue;
                }

                int r1 = (rgbActual >> 16) & 0xFF;
                int g1 = (rgbActual >> 8) & 0xFF;
                int b1 = rgbActual & 0xFF;

                int r2 = (rgbRef >> 16) & 0xFF;
                int g2 = (rgbRef >> 8) & 0xFF;
                int b2 = rgbRef & 0xFF;

                int diff = Math.max(Math.abs(r1 - r2), Math.max(Math.abs(g1 - g2), Math.abs(b1 - b2)));

                if (diff <= colorToleranceMax) {
                    // Minor anti-aliasing difference within tolerance
                    diffImage.setRGB(x, y, Color.LIGHT_GRAY.getRGB());
                } else {
                    // Significant mismatch: highlight in bright red/magenta
                    diffImage.setRGB(x, y, Color.RED.getRGB());
                    mismatchedPixels++;
                }
            }
        }

        double diffPercentage = (totalPixels > 0) ? (100.0 * mismatchedPixels / totalPixels) : 0.0;
        return new DiffResult(totalPixels, mismatchedPixels, diffPercentage, diffImage);
    }

    /**
     * Saves the highlighted diff image to disk for inspection in test reports.
     */
    public static void saveDiffImage(BufferedImage diffImage, File outputFile) throws IOException {
        outputFile.getParentFile().mkdirs();
        ImageIO.write(diffImage, "PNG", outputFile);
    }
}
