package com.converter.dxf.pdf;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * In-memory PDF Page Rotation Service.
 * Rotates designated sheets (90°, 180°, 270°) with 100% vector fidelity and zero disk retention.
 */
public class PdfRotateService {

    public static class RotateResult {
        private final byte[] pdfBytes;
        private final String filename;
        private final int totalPages;
        private final int rotatedPagesCount;
        private final int angle;

        public RotateResult(byte[] pdfBytes, String filename, int totalPages, int rotatedPagesCount, int angle) {
            this.pdfBytes = pdfBytes;
            this.filename = filename;
            this.totalPages = totalPages;
            this.rotatedPagesCount = rotatedPagesCount;
            this.angle = angle;
        }

        public byte[] getPdfBytes() {
            return pdfBytes;
        }

        public String getFilename() {
            return filename;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public int getRotatedPagesCount() {
            return rotatedPagesCount;
        }

        public int getAngle() {
            return angle;
        }
    }

    /**
     * Rotates selected pages of a PDF document by specified degrees clockwise.
     *
     * @param pdfBytes       Source PDF byte array.
     * @param angle          Rotation angle (90, 180, 270).
     * @param pageRangeExpr  Pages to rotate ("all", "even", "odd", "1-3,5").
     * @param sourceFilename Optional base filename.
     * @return RotateResult with modified PDF bytes and stats.
     */
    public static RotateResult rotate(byte[] pdfBytes, int angle, String pageRangeExpr, String sourceFilename) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF document content is empty.");
        }

        if (!isValidPdfHeader(pdfBytes)) {
            throw new IOException("The provided file is not a valid PDF document (missing %PDF- header).");
        }

        int normalizedAngle = normalizeAngle(angle);
        if (normalizedAngle == 0) {
            throw new IllegalArgumentException("Rotation angle must be 90, 180, or 270 degrees.");
        }

        String baseName = sanitizeBaseName(sourceFilename);

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) {
                throw new IOException("PDF document contains no pages.");
            }

            Set<Integer> targetPages = resolveTargetPages(pageRangeExpr, totalPages);
            int rotatedCount = 0;

            for (int i = 0; i < totalPages; i++) {
                int pageNum = i + 1;
                if (targetPages.contains(pageNum)) {
                    PDPage page = doc.getPage(i);
                    int currentRotation = page.getRotation();
                    int newRotation = (currentRotation + normalizedAngle) % 360;
                    if (newRotation < 0) newRotation += 360;
                    page.setRotation(newRotation);
                    rotatedCount++;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            byte[] outBytes = baos.toByteArray();
            String outName = String.format("%s_rotated_%ddeg.pdf", baseName, normalizedAngle);

            return new RotateResult(outBytes, outName, totalPages, rotatedCount, normalizedAngle);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to rotate PDF document: " + e.getMessage(), e);
        }
    }

    private static Set<Integer> resolveTargetPages(String expr, int totalPages) {
        if (expr == null || expr.trim().isEmpty() || "all".equalsIgnoreCase(expr.trim())) {
            Set<Integer> all = new HashSet<>();
            for (int i = 1; i <= totalPages; i++) all.add(i);
            return all;
        }

        String trimmed = expr.trim().toLowerCase();
        if ("even".equals(trimmed)) {
            Set<Integer> evens = new HashSet<>();
            for (int i = 2; i <= totalPages; i += 2) evens.add(i);
            return evens;
        }
        if ("odd".equals(trimmed)) {
            Set<Integer> odds = new HashSet<>();
            for (int i = 1; i <= totalPages; i += 2) odds.add(i);
            return odds;
        }

        return new HashSet<>(PdfSplitService.parsePageRange(expr, totalPages));
    }

    private static int normalizeAngle(int angle) {
        int mod = angle % 360;
        if (mod < 0) mod += 360;
        return (mod == 90 || mod == 180 || mod == 270) ? mod : 0;
    }

    private static boolean isValidPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        return header.contains("%PDF-");
    }

    private static String sanitizeBaseName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "document";
        String clean = filename.trim().replaceAll("(?i)\\.pdf$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isEmpty() ? "document" : clean;
    }
}

