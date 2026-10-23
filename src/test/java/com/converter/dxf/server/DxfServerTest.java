package com.converter.dxf.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
