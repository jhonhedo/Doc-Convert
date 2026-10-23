package com.converter.dxf.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

public class OleInspectTest {

    @Test
    public void scanAllImages() throws Exception {
        File dump = new File("target/ole_dump.bin");
        if (!dump.exists()) return;
        byte[] data = Files.readAllBytes(dump.toPath());
        System.out.println("Scanning " + data.length + " bytes for images...");

        int count = 0;
        // Check for BMP ('BM')
        for (int i = 0; i < data.length - 14; i++) {
            if (data[i] == 'B' && data[i+1] == 'M') {
                int b1 = data[i+2] & 0xFF;
                int b2 = data[i+3] & 0xFF;
                int b3 = data[i+4] & 0xFF;
                int b4 = data[i+5] & 0xFF;
                int bmpSize = b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
                if (bmpSize > 100 && bmpSize <= (data.length - i + 100)) {
                    System.out.printf("Candidate BMP at offset %d, declared size %d%n", i, bmpSize);
                    try {
                        byte[] sub = new byte[Math.min(bmpSize, data.length - i)];
                        System.arraycopy(data, i, sub, 0, sub.length);
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(sub));
                        if (img != null) {
                            File out = new File("target/found_bmp_" + (count++) + "_" + i + ".png");
                            ImageIO.write(img, "PNG", out);
                            System.out.println("  -> Valid BMP decoded: " + img.getWidth() + "x" + img.getHeight() + " -> " + out.getName());
                        }
                    } catch (Exception ignored) {}
                }
            }
            // Check for PNG
            if (i < data.length - 8 && (data[i] & 0xFF) == 0x89 && data[i+1] == 'P' && data[i+2] == 'N' && data[i+3] == 'G') {
                System.out.println("Found PNG at " + i);
            }
            // Check for JPEG
            if (i < data.length - 4 && (data[i] & 0xFF) == 0xFF && (data[i+1] & 0xFF) == 0xD8 && (data[i+2] & 0xFF) == 0xFF) {
                System.out.println("Found JPEG at " + i);
            }
        }
    }
}
