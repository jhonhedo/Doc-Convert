package com.converter.dxf.pdf;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * In-memory PDF Page Splitting and Sheet Extraction Service.
 * Preserves vector geometry, linework, embedded fonts, and page rotations with zero disk retention.
 */
public class PdfSplitService {

    public enum SplitMode {
        SPLIT_ZIP,  // Individual PDF per page or range, zipped
        EXTRACT_PDF // Single combined PDF of selected pages
    }

    public static class SplitResult {
        private final byte[] outputBytes;
        private final String outputFilename;
        private final String mimeType;
        private final int extractedPageCount;
        private final int sourceTotalPages;
        private final List<Integer> pageNumbers;

        public SplitResult(byte[] outputBytes, String outputFilename, String mimeType,
                           int extractedPageCount, int sourceTotalPages, List<Integer> pageNumbers) {
            this.outputBytes = outputBytes;
            this.outputFilename = outputFilename;
            this.mimeType = mimeType;
            this.extractedPageCount = extractedPageCount;
            this.sourceTotalPages = sourceTotalPages;
            this.pageNumbers = Collections.unmodifiableList(pageNumbers);
        }

        public byte[] getOutputBytes() {
            return outputBytes;
        }

        public String getOutputFilename() {
            return outputFilename;
        }

        public String getMimeType() {
            return mimeType;
        }

        public int getExtractedPageCount() {
            return extractedPageCount;
        }

        public int getSourceTotalPages() {
            return sourceTotalPages;
        }

        public List<Integer> getPageNumbers() {
            return pageNumbers;
        }
    }

    /**
     * Splits or extracts pages from a PDF document in memory.
     *
     * @param pdfBytes       Source PDF byte array.
     * @param rangeExpr      Page range expression (e.g. "1-3,5", "all", "2"). 1-based indexing.
     * @param mode           SplitMode: SPLIT_ZIP (ZIP of single sheets) or EXTRACT_PDF (unified PDF).
     * @param sourceFilename Optional base filename for naming outputs.
     * @return SplitResult containing bytes, metadata, and mime type.
     * @throws IllegalArgumentException on empty content or invalid page range.
     * @throws IOException              on PDF parse or extraction errors.
     */
    public static SplitResult process(byte[] pdfBytes, String rangeExpr, SplitMode mode, String sourceFilename) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF document content is empty.");
        }

        if (!isValidPdfHeader(pdfBytes)) {
            throw new IOException("The provided file is not a valid PDF document (missing %PDF- header).");
        }

        if (mode == null) {
            mode = SplitMode.SPLIT_ZIP;
        }

        String baseName = sanitizeBaseName(sourceFilename);

        try (PDDocument sourceDoc = PDDocument.load(new ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            int totalPages = sourceDoc.getNumberOfPages();
            if (totalPages == 0) {
                throw new IOException("PDF document contains no pages.");
            }

            List<Integer> targetPages = parsePageRange(rangeExpr, totalPages);
            if (targetPages.isEmpty()) {
                throw new IllegalArgumentException("No valid pages selected for extraction.");
            }

            if (mode == SplitMode.EXTRACT_PDF) {
                // Extract selected pages into a single new PDF document
                try (PDDocument outDoc = new PDDocument()) {
                    for (int pageNum : targetPages) {
                        PDPage srcPage = sourceDoc.getPage(pageNum - 1);
                        outDoc.importPage(srcPage);
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    outDoc.save(baos);
                    byte[] outBytes = baos.toByteArray();
                    String outName = baseName + "_extracted_sheets.pdf";
                    return new SplitResult(outBytes, outName, "application/pdf", targetPages.size(), totalPages, targetPages);
                }
            } else {
                // SPLIT_ZIP: Generate individual single-page PDF files and package into a ZIP
                ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
                    for (int pageNum : targetPages) {
                        try (PDDocument singlePageDoc = new PDDocument()) {
                            PDPage srcPage = sourceDoc.getPage(pageNum - 1);
                            singlePageDoc.importPage(srcPage);

                            ByteArrayOutputStream singleBaos = new ByteArrayOutputStream();
                            singlePageDoc.save(singleBaos);
                            byte[] singlePdfBytes = singleBaos.toByteArray();

                            String entryName = String.format("%s_sheet_%03d.pdf", baseName, pageNum);
                            ZipEntry entry = new ZipEntry(entryName);
                            zos.putNextEntry(entry);
                            zos.write(singlePdfBytes);
                            zos.closeEntry();
                        }
                    }
                    zos.finish();
                }
                byte[] zipBytes = zipBaos.toByteArray();
                String zipName = baseName + "_split_sheets.zip";
                return new SplitResult(zipBytes, zipName, "application/zip", targetPages.size(), totalPages, targetPages);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to process PDF pages: " + e.getMessage(), e);
        }
    }

    /**
     * Parses standard page range expressions: "1-3,5", "all", "1, 3, 4-6", "2".
     * Returns ordered, deduplicated 1-based page numbers.
     */
    public static List<Integer> parsePageRange(String expr, int totalPages) {
        if (expr == null || expr.trim().isEmpty() || "all".equalsIgnoreCase(expr.trim())) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= totalPages; i++) all.add(i);
            return all;
        }

        Set<Integer> pagesSet = new LinkedHashSet<>();
        String[] tokens = expr.split("[,;]");

        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.contains("-")) {
                String[] parts = trimmed.split("-", 2);
                if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                    throw new IllegalArgumentException("Invalid range format: '" + trimmed + "'. Expected format like '1-3'.");
                }
                int start, end;
                try {
                    start = Integer.parseInt(parts[0].trim());
                    end = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Non-numeric page numbers in range: '" + trimmed + "'.");
                }

                if (start < 1 || start > totalPages) {
                    throw new IllegalArgumentException("Start page " + start + " is out of bounds (document has " + totalPages + " pages).");
                }
                if (end < 1 || end > totalPages) {
                    throw new IllegalArgumentException("End page " + end + " is out of bounds (document has " + totalPages + " pages).");
                }

                int step = (start <= end) ? 1 : -1;
                for (int p = start; (step > 0) ? (p <= end) : (p >= end); p += step) {
                    pagesSet.add(p);
                }
            } else {
                int p;
                try {
                    p = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid page number: '" + trimmed + "'.");
                }
                if (p < 1 || p > totalPages) {
                    throw new IllegalArgumentException("Page " + p + " is out of bounds (document has " + totalPages + " pages).");
                }
                pagesSet.add(p);
            }
        }

        return new ArrayList<>(pagesSet);
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

