package com.converter.dxf.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.converter.dxf.pdf.PdfToolsAuditTest;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class DxfServerTest {

    private static DxfServer server;
    private static int port;

    @BeforeAll
    public static void setUp() throws Exception {
        // Find an open port by starting on 0 or random high port
        port = 18080 + (int)(Math.random() * 1000);
        server = new DxfServer("127.0.0.1", port);
        server.start();
    }

    @AfterAll
    public static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testHealthEndpoint() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/json; charset=UTF-8", conn.getContentType());

        String body = readStream(conn.getInputStream());
        assertTrue(body.contains("\"status\":\"UP\""));
        assertTrue(body.contains("\"maxUploadMb\":25"));
    }

    @Test
    public void testRobotsAndSitemap() throws Exception {
        // Test robots.txt
        URL robotsUrl = new URL("http://127.0.0.1:" + port + "/robots.txt");
        HttpURLConnection conn = (HttpURLConnection) robotsUrl.openConnection();
        assertEquals(200, conn.getResponseCode());
        String robots = readStream(conn.getInputStream());
        assertTrue(robots.contains("User-agent: *"));

        // Test sitemap.xml
        URL sitemapUrl = new URL("http://127.0.0.1:" + port + "/sitemap.xml");
        HttpURLConnection sitemapConn = (HttpURLConnection) sitemapUrl.openConnection();
        assertEquals(200, sitemapConn.getResponseCode());
        String sitemap = readStream(sitemapConn.getInputStream());
        assertTrue(sitemap.contains("<loc>https://docconvert.net/</loc>"));
    }

    @Test
    public void testValidDxfConversion() throws Exception {
        String dxf = "0\nSECTION\n2\nENTITIES\n0\nLINE\n8\n0\n10\n0.0\n20\n0.0\n11\n100.0\n21\n100.0\n0\nENDSEC\n0\nEOF\n";

        URL url = new URL("http://127.0.0.1:" + port + "/api/convert");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Filename", "test_line.dxf");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(dxf.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/pdf", conn.getContentType());
        byte[] pdfBytes = readBytes(conn.getInputStream());
        assertTrue(pdfBytes.length > 100);
        // PDF magic bytes %PDF
        assertEquals('%', pdfBytes[0]);
        assertEquals('P', pdfBytes[1]);
        assertEquals('D', pdfBytes[2]);
        assertEquals('F', pdfBytes[3]);
    }

    @Test
    public void testJsonFormatConversion() throws Exception {
        String dxf = "0\nSECTION\n2\nENTITIES\n0\nCIRCLE\n8\n0\n10\n50.0\n20\n50.0\n40\n25.0\n0\nENDSEC\n0\nEOF\n";

        URL url = new URL("http://127.0.0.1:" + port + "/api/convert");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Output-Format", "json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(dxf.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("application/json"));
        String json = readStream(conn.getInputStream());
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"pdfBase64\":"));
    }

    @Test
    public void testMalformedNonDxfInputRejected() throws Exception {
        String garbage = "THIS IS NOT A CAD FILE AT ALL. RANDOM GARBAGE DATA 12345.";

        URL url = new URL("http://127.0.0.1:" + port + "/api/convert");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(garbage.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(400, conn.getResponseCode());
        String err = readStream(conn.getErrorStream());
        assertTrue(err.contains("INVALID_DXF"));
    }

    @Test
    public void testEmptyInputRejected() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/convert");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(new byte[0]);
        }

        assertEquals(400, conn.getResponseCode());
        String err = readStream(conn.getErrorStream());
        assertTrue(err.contains("EMPTY_INPUT"));
    }

    @Test
    public void testCleanSeoRoutes() throws Exception {
        String[] routes = {"/dxf-to-pdf", "/dwg-to-pdf", "/merge-pdf", "/compress-pdf", "/tools"};
        for (String route : routes) {
            URL url = new URL("http://127.0.0.1:" + port + route);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            assertEquals(200, conn.getResponseCode(), "Failed for route: " + route);
            assertTrue(conn.getContentType().contains("text/html"), "Wrong mime type for route: " + route);
            String html = readStream(conn.getInputStream());
            assertTrue(html.contains("CADConvert"), "Missing branding for route: " + route);
        }
    }

    @Test
    public void testPdfCompressEndpoint() throws Exception {
        // Create simple dummy PDF
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdf = baos.toByteArray();
        }

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/compress?level=medium");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(pdf);
        }

        assertEquals(200, conn.getResponseCode());
        String json = readStream(conn.getInputStream());
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"compressedSize\":"));
        assertTrue(json.contains("\"pdfBase64\":"));
    }

    @Test
    public void testPdfMergeEndpoint() throws Exception {
        byte[] pdf1;
        byte[] pdf2;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdf1 = baos.toByteArray();
        }
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdf2 = baos.toByteArray();
        }

        String boundary = "----DocConvertMergeBoundary123456";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        
        // Part 1
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"doc1.pdf\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(pdf1);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // Part 2
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"doc2.pdf\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(pdf2);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));

        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/merge");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }

        assertEquals(200, conn.getResponseCode());
        String json = readStream(conn.getInputStream());
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"totalPages\":2"));
        assertTrue(json.contains("\"sourceFilesCount\":2"));
        assertTrue(json.contains("\"pdfBase64\":"));
    }

    @Test
    public void testPdfMergeCorruptInputReturns400() throws Exception {
        byte[] validPdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            validPdf = baos.toByteArray();
        }

        byte[] corruptPdf = "Corrupted non-pdf binary content".getBytes(StandardCharsets.UTF_8);

        String boundary = "----CorruptMergeBoundary123";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"valid.pdf\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(validPdf);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));

        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"corrupt.pdf\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(corruptPdf);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/merge");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }

        assertEquals(400, conn.getResponseCode(), "Corrupt PDF input must yield HTTP 400 Bad Request");
        String errorJson = readStream(conn.getErrorStream());
        assertTrue(errorJson.contains("\"code\":\"INVALID_PDF\""));
    }

    @Test
    public void testPdfCompressCorruptInputReturns400() throws Exception {
        byte[] corruptBytes = "Garbage content with no %PDF- header".getBytes(StandardCharsets.UTF_8);

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/compress");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(corruptBytes);
        }

        assertEquals(400, conn.getResponseCode(), "Corrupt PDF compress input must yield HTTP 400 Bad Request");
        String errorJson = readStream(conn.getErrorStream());
        assertTrue(errorJson.contains("\"code\":\"INVALID_PDF\""));
    }

    @Test
    public void testPdfSplitEndpoint() throws Exception {
        byte[] testPdf = PdfToolsAuditTest.createMixedPageSizesCadPdf(); // 4 pages

        // Test Split to ZIP
        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/split?mode=zip&pages=1-2");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(testPdf);
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/zip", conn.getContentType());
        assertEquals("2", conn.getHeaderField("X-Extracted-Pages"));
        assertEquals("4", conn.getHeaderField("X-Total-Pages"));
        byte[] zipBytes = readBytes(conn.getInputStream());
        assertTrue(zipBytes.length > 500);
    }

    @Test
    public void testPdfResizeEndpoint() throws Exception {
        byte[] testPdf = PdfToolsAuditTest.createVectorCadPage("Floorplan", org.apache.pdfbox.pdmodel.common.PDRectangle.A4, 0);

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/resize?size=A1&orientation=landscape&format=json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(testPdf);
        }

        assertEquals(200, conn.getResponseCode());
        String json = readStream(conn.getInputStream());
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"targetWidthPt\":2383.9"));
        assertTrue(json.contains("\"pdfBase64\":"));
    }

    @Test
    public void testPdfRotateEndpoint() throws Exception {
        byte[] testPdf = PdfToolsAuditTest.createVectorCadPage("Layout", org.apache.pdfbox.pdmodel.common.PDRectangle.A3, 0);

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/rotate?angle=90&pages=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(testPdf);
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/pdf", conn.getContentType());
        assertEquals("90", conn.getHeaderField("X-Rotation-Angle"));
        byte[] rotatedPdf = readBytes(conn.getInputStream());
        assertTrue(rotatedPdf.length > 100);
    }

    @Test
    public void testPdfStampEndpoint() throws Exception {
        byte[] testPdf = PdfToolsAuditTest.createVectorCadPage("Blueprint", org.apache.pdfbox.pdmodel.common.PDRectangle.A3, 0);

        URL url = new URL("http://127.0.0.1:" + port + "/api/pdf/stamp?text=PRELIMINARY&position=diagonal&opacity=0.6");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/pdf");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(testPdf);
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/pdf", conn.getContentType());
        assertEquals("PRELIMINARY", conn.getHeaderField("X-Stamp-Text"));
        byte[] stampedPdf = readBytes(conn.getInputStream());
        assertTrue(stampedPdf.length > 100);
    }



    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
