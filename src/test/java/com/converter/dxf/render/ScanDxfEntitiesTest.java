package com.converter.dxf.render;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

public class ScanDxfEntitiesTest {

    @Test
    public void scanEntitiesInForImageTest() throws Exception {
        scanFile(new File("C:\\Users\\johnson.hedo\\Downloads\\FOR IMAGE TEST.dxf"));
        scanFile(new File("C:\\Users\\johnson.hedo\\Downloads\\OLE1.dxf"));
    }

    private void scanFile(File f) throws Exception {
        if (!f.exists()) return;
        System.out.println("Scanning: " + f.getName());
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            boolean inEntities = false;
            Set<String> entityTypes = new HashSet<>();
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals("ENTITIES")) {
                    inEntities = true;
                }
                if (line.trim().equals("ENDSEC")) {
                    inEntities = false;
                }
                if (inEntities && line.trim().equals("0")) {
                    String type = reader.readLine();
                    if (type != null) entityTypes.add(type.trim());
                }
            }
            System.out.println("Entity types in " + f.getName() + ": " + entityTypes);
        }
    }
}
