package com.converter.dxf.pdf;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory PDF merging service utilizing Apache PDFBox.
 * Preserves vector precision, embedded fonts, and page rotations with zero disk footprint.
 */
public class PdfMergeService {

    public static class MergeResult {
        private final byte[] pdfBytes;
        private final int totalPages;
        private final int sourceFilesCount;

        public MergeResult(byte[] pdfBytes, int totalPages, int sourceFilesCount) {
            this.pdfBytes = pdfBytes;
            this.totalPages = totalPages;
            this.sourceFilesCount = sourceFilesCount;
        }

        public byte[] getPdfBytes() {
            return pdfBytes;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public int getSourceFilesCount() {
            return sourceFilesCount;
        }
    }

    /**
     * Merges multiple PDF byte arrays into a single PDF document in memory.
     *
     * @param pdfDocuments List of raw byte arrays for each PDF.
     * @return MergeResult containing merged PDF bytes and metadata.
     * @throws IllegalArgumentException if fewer than 2 documents are provided.
     * @throws IOException              if any document is corrupted or merging fails.
     */
    public static MergeResult merge(List<byte[]> pdfDocuments) throws IOException {
        if (pdfDocuments == null || pdfDocuments.size() < 2) {
            throw new IllegalArgumentException("At least 2 PDF documents are required to perform a merge.");
        }

        PDFMergerUtility merger = new PDFMergerUtility();
        List<ByteArrayInputStream> inputStreams = new ArrayList<>();
        int totalPages = 0;

        try {
            for (int i = 0; i < pdfDocuments.size(); i++) {
                byte[] bytes = pdfDocuments.get(i);
                if (bytes == null || bytes.length == 0) {
                    throw new IOException("PDF document at index " + (i + 1) + " is empty.");
                }

                // Verify valid PDF header (%PDF-)
                if (!isValidPdfHeader(bytes)) {
                    throw new IOException("File at index " + (i + 1) + " is not a valid PDF document (missing %PDF- header).");
                }

                // Quick page count probe and validation
                try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
                    if (doc.getNumberOfPages() == 0) {
                        throw new IOException("PDF document at index " + (i + 1) + " contains no pages.");
                    }
                    totalPages += doc.getNumberOfPages();
                } catch (Exception e) {
                    throw new IOException("Unable to parse PDF at index " + (i + 1) + ": " + e.getMessage(), e);
                }

                ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                inputStreams.add(bais);
                merger.addSource(bais);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            merger.setDestinationStream(outputStream);
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());

            byte[] mergedBytes = outputStream.toByteArray();
            return new MergeResult(mergedBytes, totalPages, pdfDocuments.size());

        } finally {
            for (ByteArrayInputStream is : inputStreams) {
                try {
                    is.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static boolean isValidPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), java.nio.charset.StandardCharsets.ISO_8859_1);
        return header.contains("%PDF-");
    }
}
