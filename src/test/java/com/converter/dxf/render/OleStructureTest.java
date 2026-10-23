package com.converter.dxf.render;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

public class OleStructureTest {

    @Test
    public void inspectOleStructure() throws Exception {
        File dump = new File("target/ole_dump.bin");
        if (!dump.exists()) return;
        byte[] data = Files.readAllBytes(dump.toPath());

        // Let's print the first 256 bytes
        System.out.println("--- First 128 bytes (Header before OLE) ---");
        for (int i = 0; i < Math.min(128, data.length); i++) {
            System.out.printf("%02X ", data[i]);
            if ((i + 1) % 16 == 0) System.out.println();
        }

        System.out.println("\n--- Bytes from 128 to 256 (CFBF header) ---");
        for (int i = 128; i < Math.min(256, data.length); i++) {
            System.out.printf("%02X ", data[i]);
            if ((i + 1) % 16 == 0) System.out.println();
        }

        // Search for stream names in CFBF directory entries (UTF-16LE)
        System.out.println("\n--- Searching for strings in OLE dump ---");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            char c = (char) data[i];
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                if (sb.length() >= 4) {
                    System.out.println("Offset " + (i - sb.length()) + ": " + sb.toString());
                }
                sb.setLength(0);
            }
        }
    }
}
