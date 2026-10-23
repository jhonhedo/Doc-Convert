package com.converter.dxf.render.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles DXF text decoding, escape sequence stripping, and MTEXT word-wrapping.
 */
public final class DxfTextProcessor {

    // Matches formatting tags like \A1;, \fArial|b0|i0;, \H1.5;, \W0.8;, \C1;, \S1/2;
    private static final Pattern FORMAT_CODE_PATTERN = Pattern.compile("\\\\[A-Za-z][^;]*;");

    private DxfTextProcessor() {}

    /**
     * Replaces AutoCAD special character sequences:
     * %%d -> °, %%p -> ±, %%c -> Ø, %%% -> %
     */
    public static String decodeCadSpecialChars(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceAll("(?i)%%d", "°")
                  .replaceAll("(?i)%%p", "±")
                  .replaceAll("(?i)%%c", "Ø")
                  .replaceAll("%%%", "%")
                  .replaceAll("(?i)%%[uo]", "");
    }

    /**
     * Cleans MTEXT formatting codes such as \A1;, \fArial|b0|i0;, \H1.5x;, \W0.8;, \C1;
     * Replaces \P with standard newline '\n' and \~ with non-breaking space.
     */
    public static String cleanMText(String mtext) {
        if (mtext == null || mtext.isEmpty()) return "";

        String decoded = decodeCadSpecialChars(mtext);

        // Replace AutoCAD paragraph breaks (\P) with real newline
        String result = decoded.replace("\\P", "\n");

        // Strip formatting overrides like \fArial...; or \A1; or \pxqc;
        result = FORMAT_CODE_PATTERN.matcher(result).replaceAll("");

        // Strip single letter toggles like \L, \l (underline), \O, \o (overline), \K, \k (strike)
        result = result.replaceAll("\\\\[LlOoKk]", "");

        // Replace non-breaking spaces
        result = result.replace("\\~", " ");

        // Handle escaped backslashes and brackets
        result = result.replace("\\{", "{")
                       .replace("\\}", "}")
                       .replace("\\\\", "\\");

        // Remove unescaped grouping brackets
        result = result.replace("{", "").replace("}", "");

        return result;
    }

    /**
     * Wraps lines of text according to reference rectangle width (group 41).
     * If wrapWidth <= 0, only breaks on explicit newlines.
     */
    public static List<String> wrapText(String text, double wrapWidth, double charWidthApprox) {
        List<String> finalLines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return finalLines;
        }

        String[] paragraphs = text.split("\n", -1);
        if (wrapWidth <= 0 || charWidthApprox <= 0) {
            for (String p : paragraphs) {
                finalLines.add(p);
            }
            return finalLines;
        }

        int maxCharsPerLine = Math.max(1, (int) Math.floor(wrapWidth / charWidthApprox));

        for (String paragraph : paragraphs) {
            if (paragraph.length() <= maxCharsPerLine) {
                finalLines.add(paragraph);
                continue;
            }

            String[] words = paragraph.split(" ", -1);
            StringBuilder curLine = new StringBuilder();

            for (String word : words) {
                if (curLine.length() == 0) {
                    curLine.append(word);
                } else if (curLine.length() + 1 + word.length() <= maxCharsPerLine) {
                    curLine.append(" ").append(word);
                } else {
                    finalLines.add(curLine.toString());
                    curLine = new StringBuilder(word);
                }
            }
            if (curLine.length() > 0) {
                finalLines.add(curLine.toString());
            }
        }

        return finalLines;
    }
}
