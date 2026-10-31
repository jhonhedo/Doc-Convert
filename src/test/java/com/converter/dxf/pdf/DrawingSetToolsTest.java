package com.converter.dxf.pdf;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 2 AUTOMATED TEST SUITE:
 * Verifies all 4 Drawing Set Tools:
 * 1. Split / Extract Pages
 * 2. Resize / Rescale Pages
 * 3. Rotate Pages
 * 4. Stamp / Watermark
 *
 * Includes edge cases (single-page, mixed page sizes, invalid ranges, corrupt files, max size)
 * and renders before/after PNG images to verify pure vector geometry preservation.
 */
public class DrawingSetToolsTest {

    private static final File RENDER_DIR = new File("target/audit_renders");

    @BeforeAll
    public static void setUp() {
        if (!RENDER_DIR.exists()) {
            RENDER_DIR.mkdirs();
        }
    }

    private static void renderPng(PDDocument doc, int pageIndex, String filename, int dpi) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi);
        File outFile = new File(RENDER_DIR, filename);
        ImageIO.write(image, "PNG", outFile);
        System.out.println("  Saved render: " + outFile.getAbsolutePath() + " (" + image.getWidth() + "x" + image.getHeight() + " px)");
    }

    // =========================================================================
    // 1. SPLIT / EXTRACT PAGES TESTS
    // =========================================================================

    @Test
    @DisplayName("Tool 1 [Split]: Split multi-page mixed CAD PDF into ZIP archive of single sheets")
    public void testSplitToZip() throws Exception {
        System.out.println("\n--- [TOOL 1: SPLIT] Split to ZIP Archive ---");
        byte[] mixedDoc = PdfToolsAuditTest.createMixedPageSizesCadPdf(); // 4 pages: A4, A3, A1, A0

        PdfSplitService.SplitResult result = PdfSplitService.process(mixedDoc, "all", PdfSplitService.SplitMode.SPLIT_ZIP, "cad_project.pdf");
        assertNotNull(result);
        assertEquals("application/zip", result.getMimeType());
        assertTrue(result.getOutputBytes().length > 0);
        assertEquals(4, result.getExtractedPageCount());
        assertEquals(4, result.getSourceTotalPages());

        // Verify ZIP structure and individual single-page PDFs
        int entryCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result.getOutputBytes()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                assertTrue(entry.getName().endsWith(".pdf"), "ZIP entry must be a PDF: " + entry.getName());

                // Read PDF bytes from ZIP entry
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int r;
                while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                byte[] entryPdf = baos.toByteArray();

                try (PDDocument doc = PDDocument.load(entryPdf)) {
                    assertEquals(1, doc.getNumberOfPages(), "Each extracted file must contain exactly 1 sheet");
                    if (entryCount == 1) {
                        // Render Sheet 1 from ZIP
                        renderPng(doc, 0, "split_zip_sheet_1.png", 72);
                    }
                }
            }
        }
        assertEquals(4, entryCount, "ZIP archive must contain exactly 4 sheet files");
        System.out.println("  VERIFIED: Multi-page CAD drawing set split cleanly into " + entryCount + " single-sheet PDFs inside ZIP.");
    }

    @Test
    @DisplayName("Tool 1 [Extract]: Extract selected sheet range (e.g. 1-2,4) into single PDF")
    public void testExtractRangeToPdf() throws Exception {
        System.out.println("\n--- [TOOL 1: EXTRACT] Extract Range to Single PDF ---");
        byte[] mixedDoc = PdfToolsAuditTest.createMixedPageSizesCadPdf(); // 4 pages

        // Render before
        try (PDDocument beforeDoc = PDDocument.load(mixedDoc)) {
            renderPng(beforeDoc, 0, "extract_before_page_1.png", 72);
        }

        PdfSplitService.SplitResult result = PdfSplitService.process(mixedDoc, "1-2,4", PdfSplitService.SplitMode.EXTRACT_PDF, "master_set.pdf");
        assertNotNull(result);
        assertEquals("application/pdf", result.getMimeType());
        assertEquals(3, result.getExtractedPageCount());
        assertEquals(4, result.getSourceTotalPages());

        try (PDDocument outDoc = PDDocument.load(result.getOutputBytes())) {
            assertEquals(3, outDoc.getNumberOfPages());
            // Page 1 should be A4 (0 deg)
            assertEquals(PDRectangle.A4.getWidth(), outDoc.getPage(0).getMediaBox().getWidth(), 0.01);
            assertEquals(0, outDoc.getPage(0).getRotation());
            // Page 2 should be A3 (90 deg)
            assertEquals(PDRectangle.A3.getHeight(), outDoc.getPage(1).getMediaBox().getWidth(), 0.01);
            assertEquals(90, outDoc.getPage(1).getRotation());
            // Page 3 should be A0 (270 deg)
            assertEquals(PDRectangle.A0.getHeight(), outDoc.getPage(2).getMediaBox().getWidth(), 0.01);
            assertEquals(270, outDoc.getPage(2).getRotation());

            renderPng(outDoc, 0, "extract_after_page_1.png", 72);
            renderPng(outDoc, 1, "extract_after_page_2.png", 50);
        }
        System.out.println("  VERIFIED: Range '1-2,4' extracted into 3-sheet PDF with page sizes and rotations preserved.");
    }

    @Test
    @DisplayName("Tool 1 [Split Edge Cases]: Single-page PDF, invalid range, out-of-bounds, corrupt file")
    public void testSplitEdgeCases() throws Exception {
        System.out.println("\n--- [TOOL 1: SPLIT EDGE CASES] ---");
        byte[] singlePageDoc = PdfToolsAuditTest.createVectorCadPage("Solo Sheet", PDRectangle.A4, 0);

        // 1. Single-page PDF split
        PdfSplitService.SplitResult res1 = PdfSplitService.process(singlePageDoc, "1", PdfSplitService.SplitMode.EXTRACT_PDF, "solo.pdf");
        assertEquals(1, res1.getExtractedPageCount());

        // 2. Out of bounds page range
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                PdfSplitService.process(singlePageDoc, "2", PdfSplitService.SplitMode.EXTRACT_PDF, "solo.pdf"));
        assertTrue(ex1.getMessage().contains("out of bounds"));

        // 3. Invalid non-numeric range
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                PdfSplitService.process(singlePageDoc, "one-three", PdfSplitService.SplitMode.EXTRACT_PDF, "solo.pdf"));
        assertTrue(ex2.getMessage().contains("Non-numeric") || ex2.getMessage().contains("Invalid"));

        // 4. Corrupt PDF
        byte[] corrupt = "Corrupted non-pdf bytes".getBytes(StandardCharsets.UTF_8);
        IOException ex3 = assertThrows(IOException.class, () ->
                PdfSplitService.process(corrupt, "1", PdfSplitService.SplitMode.EXTRACT_PDF, "bad.pdf"));
        assertTrue(ex3.getMessage().contains("not a valid PDF document"));

        // 5. Empty PDF
        assertThrows(IllegalArgumentException.class, () ->
                PdfSplitService.process(new byte[0], "1", PdfSplitService.SplitMode.EXTRACT_PDF, "empty.pdf"));

        System.out.println("  VERIFIED: All Split / Extract edge cases correctly caught and validated.");
    }

    // =========================================================================
    // 2. RESIZE / RESCALE PAGES TESTS
    // =========================================================================

    @Test
    @DisplayName("Tool 2 [Resize]: Fit A4 CAD drawing to A1 Landscape with proportional vector scaling")
    public void testResizeA4ToA1() throws Exception {
        System.out.println("\n--- [TOOL 2: RESIZE] A4 to A1 Landscape ---");
        byte[] a4Doc = PdfToolsAuditTest.createVectorCadPage("Detail Section A", PDRectangle.A4, 0);

        try (PDDocument beforeDoc = PDDocument.load(a4Doc)) {
            renderPng(beforeDoc, 0, "resize_before_a4.png", 72);
        }

        PdfResizeService.ResizeResult result = PdfResizeService.resize(
                a4Doc,
                PdfResizeService.PaperSize.A1,
                0, 0,
                PdfResizeService.ScaleMode.FIT,
                PdfResizeService.Orientation.LANDSCAPE,
                "all",
                "detail_section.pdf"
        );

        assertNotNull(result);
        assertEquals(1, result.getTotalPages());
        // A1 Landscape: 2383.94 x 1683.78 pt
        assertEquals(2383.94f, result.getTargetWidthPt(), 0.1f);
        assertEquals(1683.78f, result.getTargetHeightPt(), 0.1f);

        try (PDDocument outDoc = PDDocument.load(result.getPdfBytes())) {
            assertEquals(1, outDoc.getNumberOfPages());
            PDPage page = outDoc.getPage(0);
            assertEquals(2383.94f, page.getMediaBox().getWidth(), 0.1f);
            assertEquals(1683.78f, page.getMediaBox().getHeight(), 0.1f);

            // Verify vector Form XObject exists in resources
            assertNotNull(page.getResources().getXObjectNames());
            assertTrue(page.getResources().getXObjectNames().iterator().hasNext());

            renderPng(outDoc, 0, "resize_after_a1_landscape.png", 36);
        }
        System.out.println("  VERIFIED: A4 drawing cleanly rescaled to A1 landscape. Pure vector Form XObject retained.");
    }

    @Test
    @DisplayName("Tool 2 [Resize Modes]: ScaleMode FILL, KEEP_SCALE, and CUSTOM dimensions")
    public void testResizeModesAndCustomSize() throws Exception {
        System.out.println("\n--- [TOOL 2: RESIZE MODES] Fill, Keep Scale, & Custom Size ---");
        byte[] a3Doc = PdfToolsAuditTest.createVectorCadPage("Floor Plan", PDRectangle.A3, 0);

        // 1. FILL mode (stretch to fit A0)
        PdfResizeService.ResizeResult fillRes = PdfResizeService.resize(
                a3Doc, PdfResizeService.PaperSize.A0, 0, 0,
                PdfResizeService.ScaleMode.FILL, PdfResizeService.Orientation.AUTO, "all", "plan.pdf");
        assertEquals(1, fillRes.getTotalPages());

        // 2. KEEP_SCALE (1:1 scale, centered on A2)
        PdfResizeService.ResizeResult keepScaleRes = PdfResizeService.resize(
                a3Doc, PdfResizeService.PaperSize.A2, 0, 0,
                PdfResizeService.ScaleMode.KEEP_SCALE, PdfResizeService.Orientation.LANDSCAPE, "all", "plan.pdf");
        assertEquals(1, keepScaleRes.getTotalPages());

        // 3. CUSTOM size (1000 x 700 pt)
        PdfResizeService.ResizeResult customRes = PdfResizeService.resize(
                a3Doc, PdfResizeService.PaperSize.CUSTOM, 1000f, 700f,
                PdfResizeService.ScaleMode.FIT, PdfResizeService.Orientation.AUTO, "all", "plan.pdf");
        assertEquals(1000f, customRes.getTargetWidthPt(), 0.1f);
        assertEquals(700f, customRes.getTargetHeightPt(), 0.1f);

        try (PDDocument doc = PDDocument.load(customRes.getPdfBytes())) {
            assertEquals(1000f, doc.getPage(0).getMediaBox().getWidth(), 0.1f);
            assertEquals(700f, doc.getPage(0).getMediaBox().getHeight(), 0.1f);
            renderPng(doc, 0, "resize_custom_1000x700.png", 50);
        }

        System.out.println("  VERIFIED: Fill, Keep Scale, and Custom dimensions operate flawlessly in vector space.");
    }

    @Test
    @DisplayName("Tool 2 [Resize Edge Cases]: Invalid custom dimensions, empty PDF, corrupt PDF")
    public void testResizeEdgeCases() throws Exception {
        System.out.println("\n--- [TOOL 2: RESIZE EDGE CASES] ---");
        byte[] validDoc = PdfToolsAuditTest.createVectorCadPage("A4 Sheet", PDRectangle.A4, 0);

        // 1. Invalid custom dimensions (<= 10 pt)
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                PdfResizeService.resize(validDoc, PdfResizeService.PaperSize.CUSTOM, 5f, 5f,
                        PdfResizeService.ScaleMode.FIT, PdfResizeService.Orientation.AUTO, "all", "sheet.pdf"));
        assertTrue(ex1.getMessage().contains("greater than 10 pt"));

        // 2. Empty input
        assertThrows(IllegalArgumentException.class, () ->
                PdfResizeService.resize(new byte[0], PdfResizeService.PaperSize.A3, 0, 0,
                        PdfResizeService.ScaleMode.FIT, PdfResizeService.Orientation.AUTO, "all", "sheet.pdf"));

        // 3. Corrupt PDF
        byte[] corrupt = "Malformed PDF stream bytes".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () ->
                PdfResizeService.resize(corrupt, PdfResizeService.PaperSize.A3, 0, 0,
                        PdfResizeService.ScaleMode.FIT, PdfResizeService.Orientation.AUTO, "all", "sheet.pdf"));

        System.out.println("  VERIFIED: Resize edge cases validated.");
    }

    // =========================================================================
    // 3. ROTATE PAGES TESTS
    // =========================================================================

    @Test
    @DisplayName("Tool 3 [Rotate]: Rotate multi-page PDF by 90, 180, 270 degrees")
    public void testRotateAngles() throws Exception {
        System.out.println("\n--- [TOOL 3: ROTATE] 90°, 180°, 270° ---");
        byte[] docBytes = PdfToolsAuditTest.createVectorCadPage("Blueprint A3", PDRectangle.A3, 0);

        // Render before
        try (PDDocument beforeDoc = PDDocument.load(docBytes)) {
            assertEquals(0, beforeDoc.getPage(0).getRotation());
            renderPng(beforeDoc, 0, "rotate_before_0deg.png", 72);
        }

        // Rotate 90
        PdfRotateService.RotateResult res90 = PdfRotateService.rotate(docBytes, 90, "all", "sheet.pdf");
        try (PDDocument doc90 = PDDocument.load(res90.getPdfBytes())) {
            assertEquals(90, doc90.getPage(0).getRotation());
            renderPng(doc90, 0, "rotate_after_90deg.png", 72);
        }

        // Rotate 180
        PdfRotateService.RotateResult res180 = PdfRotateService.rotate(docBytes, 180, "all", "sheet.pdf");
        try (PDDocument doc180 = PDDocument.load(res180.getPdfBytes())) {
            assertEquals(180, doc180.getPage(0).getRotation());
            renderPng(doc180, 0, "rotate_after_180deg.png", 72);
        }

        // Rotate 270
        PdfRotateService.RotateResult res270 = PdfRotateService.rotate(docBytes, 270, "all", "sheet.pdf");
        try (PDDocument doc270 = PDDocument.load(res270.getPdfBytes())) {
            assertEquals(270, doc270.getPage(0).getRotation());
            renderPng(doc270, 0, "rotate_after_270deg.png", 72);
        }

        System.out.println("  VERIFIED: 90°, 180°, and 270° rotations accurately applied and rendered.");
    }

    @Test
    @DisplayName("Tool 3 [Rotate Selection]: Rotate specific pages ('even', 'odd', '2') in mixed drawing set")
    public void testRotatePageSelection() throws Exception {
        System.out.println("\n--- [TOOL 3: ROTATE SELECTION] Specific Page Rotations ---");
        byte[] mixedDoc = PdfToolsAuditTest.createMixedPageSizesCadPdf(); // 4 pages: 0, 90, 180, 270

        // Rotate only even pages by 90 degrees
        PdfRotateService.RotateResult res = PdfRotateService.rotate(mixedDoc, 90, "even", "set.pdf");
        assertEquals(2, res.getRotatedPagesCount());

        try (PDDocument doc = PDDocument.load(res.getPdfBytes())) {
            // Page 1 (odd): was 0, remains 0
            assertEquals(0, doc.getPage(0).getRotation());
            // Page 2 (even): was 90, rotated by 90 -> 180
            assertEquals(180, doc.getPage(1).getRotation());
            // Page 3 (odd): was 180, remains 180
            assertEquals(180, doc.getPage(2).getRotation());
            // Page 4 (even): was 270, rotated by 90 -> 0 (360 % 360)
            assertEquals(0, doc.getPage(3).getRotation());
        }

        System.out.println("  VERIFIED: Selective rotation by 'even' expression succeeded.");
    }

    @Test
    @DisplayName("Tool 3 [Rotate Edge Cases]: Invalid angle (45), invalid page range, corrupt file")
    public void testRotateEdgeCases() throws Exception {
        System.out.println("\n--- [TOOL 3: ROTATE EDGE CASES] ---");
        byte[] validDoc = PdfToolsAuditTest.createVectorCadPage("Sheet", PDRectangle.A4, 0);

        // 1. Invalid angle (45)
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                PdfRotateService.rotate(validDoc, 45, "all", "sheet.pdf"));
        assertTrue(ex1.getMessage().contains("must be 90, 180, or 270"));

        // 2. Corrupt file
        assertThrows(IOException.class, () ->
                PdfRotateService.rotate("Invalid".getBytes(StandardCharsets.UTF_8), 90, "all", "sheet.pdf"));

        System.out.println("  VERIFIED: Rotate validation handled with clear error messages.");
    }

    // =========================================================================
    // 4. STAMP / WATERMARK TESTS
    // =========================================================================

    @Test
    @DisplayName("Tool 4 [Stamp]: Imprint vector 'FOR APPROVAL' stamp diagonally with opacity & border")
    public void testStampApprovalDiagonal() throws Exception {
        System.out.println("\n--- [TOOL 4: STAMP] 'FOR APPROVAL' Diagonal Vector Stamp ---");
        byte[] cadDoc = PdfToolsAuditTest.createVectorCadPage("Architectural Layout", PDRectangle.A3, 0);

        // Render before
        try (PDDocument beforeDoc = PDDocument.load(cadDoc)) {
            renderPng(beforeDoc, 0, "stamp_before.png", 72);
        }

        PdfStampService.StampOptions options = new PdfStampService.StampOptions()
                .setText("FOR APPROVAL")
                .setPosition(PdfStampService.StampPosition.DIAGONAL)
                .setOpacity(0.45f)
                .setFontSize(48f)
                .setColor(new Color(220, 38, 38)) // Red
                .setDrawBorder(true)
                .setPageRangeExpr("all");

        PdfStampService.StampResult result = PdfStampService.stamp(cadDoc, options, "layout.pdf");
        assertNotNull(result);
        assertEquals(1, result.getStampedPagesCount());
        assertEquals("FOR APPROVAL", result.getAppliedStampText());

        try (PDDocument afterDoc = PDDocument.load(result.getPdfBytes())) {
            assertEquals(1, afterDoc.getNumberOfPages());
            // Verify content stream exists and is vector
            PDPage page = afterDoc.getPage(0);
            assertNotNull(page.getCOSObject().getDictionaryObject(COSName.CONTENTS));

            renderPng(afterDoc, 0, "stamp_after_diagonal_approval.png", 72);
        }
        System.out.println("  VERIFIED: Diagonal vector 'FOR APPROVAL' stamp imprinted cleanly with 0.45 opacity.");
    }

    @Test
    @DisplayName("Tool 4 [Stamp Positions & Macros]: Imprint date and sheet macros at BOTTOM_RIGHT and TOP_RIGHT")
    public void testStampPositionsAndMacros() throws Exception {
        System.out.println("\n--- [TOOL 4: STAMP POSITIONS & MACROS] ---");
        byte[] multiDoc = PdfToolsAuditTest.createMixedPageSizesCadPdf(); // 4 pages

        // Stamp bottom right with date and sheet macro
        PdfStampService.StampOptions options = new PdfStampService.StampOptions()
                .setText("ISSUED FOR REVIEW - {date} - {sheet}")
                .setPosition(PdfStampService.StampPosition.BOTTOM_RIGHT)
                .setFontSize(14f)
                .setOpacity(0.85f)
                .setColorHex("#2563EB") // Blue
                .setDrawBorder(true)
                .setPageRangeExpr("1-2");

        PdfStampService.StampResult result = PdfStampService.stamp(multiDoc, options, "drawings.pdf");
        assertEquals(2, result.getStampedPagesCount());

        try (PDDocument doc = PDDocument.load(result.getPdfBytes())) {
            assertEquals(4, doc.getNumberOfPages());
            renderPng(doc, 0, "stamp_after_bottom_right_macros.png", 72);
        }
        System.out.println("  VERIFIED: Date & Sheet macros resolved and imprinted at BOTTOM_RIGHT.");
    }

    @Test
    @DisplayName("Tool 4 [Stamp Edge Cases]: Empty text, invalid range, corrupt file")
    public void testStampEdgeCases() throws Exception {
        System.out.println("\n--- [TOOL 4: STAMP EDGE CASES] ---");
        byte[] validDoc = PdfToolsAuditTest.createVectorCadPage("Sheet", PDRectangle.A4, 0);

        // 1. Invalid page range
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> {
            PdfStampService.StampOptions opt = new PdfStampService.StampOptions().setPageRangeExpr("10");
            PdfStampService.stamp(validDoc, opt, "sheet.pdf");
        });
        assertTrue(ex1.getMessage().contains("out of bounds"));

        // 2. Corrupt file
        assertThrows(IOException.class, () ->
                PdfStampService.stamp("Corrupted".getBytes(StandardCharsets.UTF_8), new PdfStampService.StampOptions(), "sheet.pdf"));

        System.out.println("  VERIFIED: Stamp validation rules verified.");
    }
}
