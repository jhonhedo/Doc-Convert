package com.converter.dxf.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;

public class TestPdfboxImage {

    @Test
    public void testDrawImageMatrix() throws Exception {
        File mapFile = new File("target/decoded_ole_map.png");
        BufferedImage img = mapFile.exists() ? ImageIO.read(mapFile) : new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        PDImageXObject pdImg = LosslessFactory.createFromImage(doc, img);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // Draw at x=100, y=200, width=300, height=200
            AffineTransform at = new AffineTransform();
            at.translate(100, 200);
            at.scale(300, 200);

            Matrix m = new Matrix(
                (float) at.getScaleX(), (float) at.getShearY(),
                (float) at.getShearX(), (float) at.getScaleY(),
                (float) at.getTranslateX(), (float) at.getTranslateY()
            );
            cs.drawImage(pdImg, m);
        }

        File pdfOut = new File("target/test_img_out.pdf");
        doc.save(pdfOut);
        doc.close();

        // Render back to PNG and verify
        try (PDDocument readDoc = PDDocument.load(pdfOut)) {
            PDFRenderer renderer = new PDFRenderer(readDoc);
            BufferedImage pageImg = renderer.renderImageWithDPI(0, 150);
            ImageIO.write(pageImg, "PNG", new File("target/test_img_rendered.png"));
            System.out.println("Successfully rendered test PDF image!");
        }
    }
}
