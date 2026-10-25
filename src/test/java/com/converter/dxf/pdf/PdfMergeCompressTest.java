package com.converter.dxf.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class PdfMergeCompressTest {

    private byte[] createDummyPdf(String text, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 14);
                    cs.newLineAtOffset(100, 700);
                    cs.showText(text + " - Page " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    public void testMergeSuccess() throws IOException {
        byte[] pdf1 = createDummyPdf("Document A", 2);
        byte[] pdf2 = createDummyPdf("Document B", 3);

        PdfMergeService.MergeResult result = PdfMergeService.merge(Arrays.asList(pdf1, pdf2));
        assertNotNull(result);
        assertEquals(5, result.getTotalPages());
        assertEquals(2, result.getSourceFilesCount());
        assertTrue(result.getPdfBytes().length > 0);

        // Verify readable with PDFBox
        try (PDDocument merged = PDDocument.load(result.getPdfBytes())) {
            assertEquals(5, merged.getNumberOfPages());
        }
    }

    @Test
    public void testMergeRequiresAtLeastTwo() {
        assertThrows(IllegalArgumentException.class, () -> {
            PdfMergeService.merge(Collections.singletonList(createDummyPdf("Solo", 1)));
        });
    }

    @Test
    public void testCompress() throws IOException {
        byte[] original = createDummyPdf("High Fidelity Blueprint Drawing", 3);
        PdfCompressService.CompressResult res = PdfCompressService.compress(original, PdfCompressService.CompressionLevel.MEDIUM);
        assertNotNull(res);
        assertEquals(3, res.getTotalPages());
        assertTrue(res.getCompressedSize() > 0);
        assertTrue(res.getPdfBytes().length > 0);

        // Verify valid PDF output
        try (PDDocument doc = PDDocument.load(res.getPdfBytes())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    public void testCompressFinalWithImage() throws IOException {
        java.io.File file = new java.io.File("FINAL with image.pdf");
        if (file.exists()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            try (PDDocument d = PDDocument.load(bytes)) {
                for (PDPage p : d.getPages()) {
                    PDResources r = p.getResources();
                    if (r != null) {
                        for (org.apache.pdfbox.cos.COSName n : r.getXObjectNames()) {
                            org.apache.pdfbox.pdmodel.graphics.PDXObject xo = r.getXObject(n);
                            if (xo instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                                org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject im = (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) xo;
                                System.out.println("Image " + n.getName() + " filter=" + im.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.FILTER) + " len=" + im.getCOSObject().getInt(org.apache.pdfbox.cos.COSName.LENGTH) + " size=" + im.getWidth() + "x" + im.getHeight());
                            }
                        }
                    }
                }
            }
            for (PdfCompressService.CompressionLevel lvl : PdfCompressService.CompressionLevel.values()) {
                PdfCompressService.CompressResult res = PdfCompressService.compress(bytes, lvl);
                System.out.println(lvl + " result: orig=" + res.getOriginalSize() + 
                                   ", comp=" + res.getCompressedSize() + 
                                   ", savings=" + String.format("%.1f%%", res.getSavingsPercentage()) + 
                                   ", images=" + res.getOptimizedImagesCount());
                
                assertTrue(res.getSavingsPercentage() > 0.0, "Expected positive savings for " + lvl);
                assertTrue(res.getCompressedSize() < res.getOriginalSize());
                assertEquals(1, res.getOptimizedImagesCount());

                // Verify output is a valid, readable PDF
                try (PDDocument doc = PDDocument.load(res.getPdfBytes())) {
                    assertEquals(res.getTotalPages(), doc.getNumberOfPages());
                }
            }
        }
    }
}
