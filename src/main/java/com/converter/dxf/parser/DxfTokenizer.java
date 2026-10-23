package com.converter.dxf.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Clean-room DXF group-code tokenizer reading (code, value) pairs.
 */
public final class DxfTokenizer {

    public static final class Group {
        public final int code;
        public final String value;

        public Group(int code, String value) {
            this.code = code;
            this.value = value;
        }

        public int code() { return code; }
        public String value() { return value; }

        public double asDouble() {
            try {
                return Double.parseDouble(value.trim());
            } catch (Exception e) {
                return 0.0;
            }
        }

        public int asInt() {
            try {
                return Integer.parseInt(value.trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private DxfTokenizer() {}

    public static List<Group> tokenize(String dxfContent) throws IOException {
        List<Group> groups = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(dxfContent))) {
            String codeLine;
            while ((codeLine = reader.readLine()) != null) {
                codeLine = codeLine.trim();
                if (codeLine.isEmpty()) continue;
                String valLine = reader.readLine();
                if (valLine == null) break;

                try {
                    int code = Integer.parseInt(codeLine);
                    groups.add(new Group(code, valLine.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return groups;
    }
}
