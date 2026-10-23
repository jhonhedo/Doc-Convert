package com.converter.dxf.test;

import com.converter.dxf.DxfToPdfConverter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class VisualRegressionIntegrationTest {

    private static final String SAMPLE_DXF =
        "0\nSECTION\n2\nENTITIES\n" +
        "0\nLINE\n8\nWALL\n10\n20.0\n20\n20.0\n30\n0.0\n11\n180.0\n21\n20.0\n31\n0.0\n370\n70\n62\n1\n" +
        "0\nCIRCLE\n8\nCOLUMN\n10\n100.0\n20\n100.0\n30\n0.0\n40\n30.0\n62\n5\n" +
        "0\nHATCH\n8\nHATCH_SOLID\n70\n1\n" +
        "10\n30.0\n20\n30.0\n10\n70.0\n20\n30.0\n10\n70.0\n20\n70.0\n10\n30.0\n20\n70.0\n62\n3\n" +
        "0\nENDSEC\n0\nEOF\n";

    private static final String MODIFIED_DXF =
        "0\nSECTION\n2\nENTITIES\n" +
        "0\nLINE\n8\nWALL\n10\n20.0\n20\n20.0\n30\n0.0\n11\n180.0\n21\n20.0\n31\n0.0\n370\n70\n62\n1\n" +
        "0\nCIRCLE\n8\nCOLUMN\n10\n100.0\n20\n100.0\n30\n0.0\n40\n50.0\n62\n5\n" +
        "0\nENDSEC\n0\nEOF\n";

    @Test
    public void testVisualRegressionConsistency() throws IOException {
        BufferedImage img1;
        try (PDDocument doc1 = DxfToPdfConverter.convertToPdfDocument(SAMPLE_DXF)) {
            PDFRenderer renderer1 = new PDFRenderer(doc1);
            img1 = renderer1.renderImageWithDPI(0, 15);
        }

        BufferedImage img2;
        try (PDDocument doc2 = DxfToPdfConverter.convertToPdfDocument(SAMPLE_DXF)) {
            PDFRenderer renderer2 = new PDFRenderer(doc2);
            img2 = renderer2.renderImageWithDPI(0, 15);
        }

        // Visual comparison between identical renders must be 100% match
        VisualRegressionComparator.DiffResult result = VisualRegressionComparator.compare(img1, img2, 5);
        assertEquals(0, result.mismatchedPixels());
        assertEquals(0.0, result.diffPercentage(), 1e-4);
        assertTrue(result.isIdentical());
    }

    @Test
    public void testVisualRegressionDetectsChanges() throws IOException {
        BufferedImage imgBase;
        try (PDDocument docBase = DxfToPdfConverter.convertToPdfDocument(SAMPLE_DXF)) {
            PDFRenderer rendererBase = new PDFRenderer(docBase);
            imgBase = rendererBase.renderImageWithDPI(0, 15);
        }

        BufferedImage imgMod;
        try (PDDocument docMod = DxfToPdfConverter.convertToPdfDocument(MODIFIED_DXF)) {
            PDFRenderer rendererMod = new PDFRenderer(docMod);
            imgMod = rendererMod.renderImageWithDPI(0, 15);
        }

        // Compare modified drawing against baseline
        VisualRegressionComparator.DiffResult diff = VisualRegressionComparator.compare(imgMod, imgBase, 5);

        // Must detect the visual differences (missing hatch and larger circle)
        assertFalse(diff.isIdentical());
        assertTrue(diff.mismatchedPixels() > 0);
        assertTrue(diff.diffPercentage() > 0.1);

        // Save diff image artifact to target folder for inspection
        File diffReportFile = new File("target/diff-reports/visual_diff_test.png");
        VisualRegressionComparator.saveDiffImage(diff.diffImage(), diffReportFile);
        assertTrue(diffReportFile.exists());
    }

    @Test
    public void testInvisibleEntityGroup60Ignored() throws IOException {
        String baseDxf =
            "0\nSECTION\n2\nENTITIES\n" +
            "0\nLINE\n8\n0\n10\n0.0\n20\n0.0\n11\n100.0\n21\n100.0\n" +
            "0\nENDSEC\n0\nEOF\n";

        String dxfWithInvisibleCircle =
            "0\nSECTION\n2\nENTITIES\n" +
            "0\nLINE\n8\n0\n10\n0.0\n20\n0.0\n11\n100.0\n21\n100.0\n" +
            "0\nCIRCLE\n8\n0\n60\n1\n10\n50.0\n20\n50.0\n40\n40.0\n" +
            "0\nENDSEC\n0\nEOF\n";

        try (PDDocument doc1 = DxfToPdfConverter.convertToPdfDocument(baseDxf);
             PDDocument doc2 = DxfToPdfConverter.convertToPdfDocument(dxfWithInvisibleCircle)) {
            PDFRenderer r1 = new PDFRenderer(doc1);
            PDFRenderer r2 = new PDFRenderer(doc2);
            BufferedImage img1 = r1.renderImageWithDPI(0, 15);
            BufferedImage img2 = r2.renderImageWithDPI(0, 15);

            VisualRegressionComparator.DiffResult diff = VisualRegressionComparator.compare(img1, img2, 5);
            assertTrue(diff.isIdentical(), "Invisible entity (group code 60 = 1) must be ignored and produce identical output to when it is absent");
        }
    }
}
