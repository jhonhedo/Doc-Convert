package com.converter.dxf.render.image;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Calculates 2D affine transformation matrices and decodes raster images
 * for DXF IMAGE and OLE2FRAME entities.
 */
public final class DxfImageHelper {

    private DxfImageHelper() {}

    /**
     * Computes the AffineTransform to map a normalized [0..1] x [0..1] image
     * to drawing world coordinates for an IMAGE entity.
     *
     * @param origin Insertion point (groups 10, 20)
     * @param uVec Pixel step vector along width (groups 11, 21)
     * @param vVec Pixel step vector along height (groups 12, 22)
     * @param pixelWidth Image display width in pixels (group 13)
     * @param pixelHeight Image display height in pixels (group 23)
     * @return AffineTransform for rendering
     */
    public static AffineTransform calculateImageTransform(
            Point2D.Double origin,
            Point2D.Double uVec,
            Point2D.Double vVec,
            double pixelWidth,
            double pixelHeight) {

        double m00 = uVec.x * pixelWidth;
        double m10 = uVec.y * pixelWidth;
        double m01 = vVec.x * pixelHeight;
        double m11 = vVec.y * pixelHeight;
        double m02 = origin.x;
        double m12 = origin.y;

        return new AffineTransform(m00, m10, m01, m11, m02, m12);
    }

    /**
     * Computes the AffineTransform to map a normalized [0..1] x [0..1] image
     * to drawing world coordinates for an OLE2FRAME entity.
     *
     * @param p10 Corner point (groups 10, 20)
     * @param p11 Opposite corner point (groups 11, 21)
     * @return AffineTransform for rendering
     */
    public static AffineTransform calculateOleTransform(Point2D.Double p10, Point2D.Double p11) {
        double minX = Math.min(p10.x, p11.x);
        double maxX = Math.max(p10.x, p11.x);
        double minY = Math.min(p10.y, p11.y);
        double maxY = Math.max(p10.y, p11.y);

        double w = Math.max(1e-4, maxX - minX);
        double h = Math.max(1e-4, maxY - minY);

        return new AffineTransform(w, 0.0, 0.0, h, minX, minY);
    }

    /**
     * Parses binary data from DXF group 310 hex strings.
     *
     * @param hexLines List of hex string chunks
     * @return byte array of decoded binary data
     */
    public static byte[] parseHexData(List<String> hexLines) {
        if (hexLines == null || hexLines.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (String line : hexLines) {
            String clean = line.replaceAll("\\s+", "");
            if (!clean.isEmpty()) {
                try {
                    int len = clean.length();
                    byte[] chunk = new byte[len / 2];
                    for (int i = 0; i < len - 1; i += 2) {
                        int d1 = Character.digit(clean.charAt(i), 16);
                        int d2 = Character.digit(clean.charAt(i + 1), 16);
                        if (d1 >= 0 && d2 >= 0) {
                            chunk[i / 2] = (byte) ((d1 << 4) + d2);
                        }
                    }
                    baos.write(chunk);
                } catch (Exception ignored) {
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * Extracts an embedded raster image (BMP, PNG, JPEG, or DIB) from raw OLE binary data.
     *
     * @param data Raw binary data from OLE2FRAME group 310
     * @return Decoded BufferedImage, or null if no valid image is found
     */
    public static BufferedImage extractImageFromOleData(byte[] data) {
        if (data == null || data.length < 14) {
            return null;
        }

        // 1. Search for Windows BMP with 'BM' magic header
        for (int i = 0; i < data.length - 14; i++) {
            if (data[i] == 'B' && data[i + 1] == 'M') {
                int b1 = data[i + 2] & 0xFF;
                int b2 = data[i + 3] & 0xFF;
                int b3 = data[i + 4] & 0xFF;
                int b4 = data[i + 5] & 0xFF;
                int fileSize = b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);

                if (fileSize > 14 && fileSize <= (data.length - i + 1024)) {
                    int len = Math.min(fileSize, data.length - i);
                    try {
                        byte[] bmpData = Arrays.copyOfRange(data, i, i + len);
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bmpData));
                        if (img != null) {
                            return img;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 2. Search for PNG magic header (89 50 4E 47 0D 0A 1A 0A)
        for (int i = 0; i < data.length - 8; i++) {
            if ((data[i] & 0xFF) == 0x89 && data[i + 1] == 'P' && data[i + 2] == 'N' && data[i + 3] == 'G') {
                try {
                    byte[] pngData = Arrays.copyOfRange(data, i, data.length);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
                    if (img != null) {
                        return img;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 3. Search for JPEG magic header (FF D8 FF)
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8 && (data[i + 2] & 0xFF) == 0xFF) {
                try {
                    byte[] jpgData = Arrays.copyOfRange(data, i, data.length);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpgData));
                    if (img != null) {
                        return img;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 4. Search for raw DIB (BITMAPINFOHEADER starting with header size = 40)
        for (int i = 0; i < data.length - 40; i++) {
            int headerSize = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8) |
                    ((data[i + 2] & 0xFF) << 16) | ((data[i + 3] & 0xFF) << 24);
            if (headerSize == 40) {
                int planes = (data[i + 12] & 0xFF) | ((data[i + 13] & 0xFF) << 8);
                int bitCount = (data[i + 14] & 0xFF) | ((data[i + 15] & 0xFF) << 8);
                if (planes == 1 && (bitCount == 1 || bitCount == 4 || bitCount == 8 || bitCount == 16 || bitCount == 24 || bitCount == 32)) {
                    int w = (data[i + 4] & 0xFF) | ((data[i + 5] & 0xFF) << 8) |
                            ((data[i + 6] & 0xFF) << 16) | ((data[i + 7] & 0xFF) << 24);
                    int h = (data[i + 8] & 0xFF) | ((data[i + 9] & 0xFF) << 8) |
                            ((data[i + 10] & 0xFF) << 16) | ((data[i + 11] & 0xFF) << 24);
                    if (w > 0 && w < 30000 && h != 0 && Math.abs(h) < 30000) {
                        // Synthesize 14-byte BITMAPFILEHEADER
                        int colorTableEntries = (bitCount <= 8) ? (1 << bitCount) : 0;
                        int offsetToPixels = 14 + 40 + colorTableEntries * 4;
                        int dibSize = data.length - i;
                        int totalFileSize = 14 + dibSize;

                        byte[] fullBmp = new byte[totalFileSize];
                        fullBmp[0] = 'B';
                        fullBmp[1] = 'M';
                        fullBmp[2] = (byte) (totalFileSize & 0xFF);
                        fullBmp[3] = (byte) ((totalFileSize >> 8) & 0xFF);
                        fullBmp[4] = (byte) ((totalFileSize >> 16) & 0xFF);
                        fullBmp[5] = (byte) ((totalFileSize >> 24) & 0xFF);
                        fullBmp[10] = (byte) (offsetToPixels & 0xFF);
                        fullBmp[11] = (byte) ((offsetToPixels >> 8) & 0xFF);
                        fullBmp[12] = (byte) ((offsetToPixels >> 16) & 0xFF);
                        fullBmp[13] = (byte) ((offsetToPixels >> 24) & 0xFF);
                        System.arraycopy(data, i, fullBmp, 14, dibSize);

                        try {
                            BufferedImage img = ImageIO.read(new ByteArrayInputStream(fullBmp));
                            if (img != null) {
                                return img;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        return null;
    }
}
