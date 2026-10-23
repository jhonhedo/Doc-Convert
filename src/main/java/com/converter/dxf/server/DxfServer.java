package com.converter.dxf.server;

import com.converter.dxf.DxfToPdfConverter;
import com.converter.dxf.render.hatch.DynamicHatchRuleEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Self-contained, production-ready HTTP server providing REST API and CloudConvert-style
 * Web UI for the DXF to PDF vector conversion engine.
 */
public class DxfServer {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final long START_TIME = System.currentTimeMillis();
    private static final String STATIC_DIR_OVERRIDE = "src/main/resources/static";

    private final HttpServer server;
    private final int port;
    private final String host;

    public DxfServer(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));

        setupRoutes();
    }

    private void setupRoutes() {
        // Health check endpoint
        server.createContext("/api/health", new HealthHandler());

        // Conversion endpoint (returns raw PDF binary or JSON with base64)
        server.createContext("/api/convert", new ConvertHandler());

        // Preview endpoint (converts & renders Page 1 to PNG thumbnail)
        server.createContext("/api/preview", new PreviewHandler());

        // Sample DXF download endpoint
        server.createContext("/api/sample", new SampleHandler());

        // Static files & UI handler (root and /static/*)
        server.createContext("/", new StaticFileHandler());
    }

    public void start() {
        server.start();
        System.out.println("=================================================================");
        System.out.println("   CADConvert - DXF to Vector PDF Web Application & REST API");
        System.out.println("=================================================================");
        System.out.println(" Server running at: http://localhost:" + port + "/");
        System.out.println(" REST API Endpoint: http://localhost:" + port + "/api/convert");
        System.out.println(" Preview Endpoint:  http://localhost:" + port + "/api/preview");
        System.out.println(" Health Check:      http://localhost:" + port + "/api/health");
        System.out.println(" Sample CAD File:   http://localhost:" + port + "/api/sample");
        System.out.println("=================================================================");
    }

    public void stop(int delay) {
        server.stop(delay);
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String host = DEFAULT_HOST;

        // Parse CLI arguments: --port 8080 --host 0.0.0.0
        for (int i = 0; i < args.length; i++) {
            if (("--port".equals(args[i]) || "-p".equals(args[i])) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {}
            } else if (("--host".equals(args[i]) || "-h".equals(args[i])) && i + 1 < args.length) {
                host = args[++i];
            }
        }

        // Support environment variables / system properties
        String envPort = System.getenv("PORT");
        if (envPort == null) envPort = System.getProperty("port");
        if (envPort != null) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }

        try {
            DxfServer app = new DxfServer(host, port);
            app.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down CADConvert Server...");
                app.stop(1);
            }));
        } catch (IOException e) {
            System.err.println("Fatal: Failed to start DxfServer: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            long uptimeSec = (System.currentTimeMillis() - START_TIME) / 1000;
            Runtime runtime = Runtime.getRuntime();
            long freeMem = runtime.freeMemory() / (1024 * 1024);
            long totalMem = runtime.totalMemory() / (1024 * 1024);

            String json = String.format(
                    "{\"status\":\"UP\",\"service\":\"CADConvert DXF to PDF\",\"version\":\"1.0.0\"," +
                    "\"uptimeSeconds\":%d,\"availableProcessors\":%d,\"memoryMb\":{\"free\":%d,\"total\":%d}}",
                    uptimeSec, runtime.availableProcessors(), freeMem, totalMem
            );

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class ConvertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed. Use POST.");
                return;
            }

            try {
                ParsedUpload upload = parseRequest(exchange);
                if (upload.dxfContent == null || upload.dxfContent.trim().isEmpty()) {
                    sendError(exchange, 400, "Missing DXF file content in request body.");
                    return;
                }

                // Setup Hatch Engine based on layers if provided
                DynamicHatchRuleEngine hatchEngine = DynamicHatchRuleEngine.getDefault();
                if (upload.targetLayers != null && !upload.targetLayers.isEmpty()) {
                    hatchEngine = new DynamicHatchRuleEngine(upload.targetLayers);
                }

                // Render vector PDF in-memory
                byte[] pdfBytes;
                byte[] previewBytes = null;

                try (PDDocument doc = DxfToPdfConverter.convertToPdfDocument(upload.dxfContent, hatchEngine, null)) {
                    ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
                    doc.save(pdfBaos);
                    pdfBytes = pdfBaos.toByteArray();

                    // If JSON response requested or preview requested
                    if (upload.returnJson || "json".equalsIgnoreCase(upload.format)) {
                        try {
                            previewBytes = renderPreviewImage(doc, upload.dpi);
                        } catch (Exception e) {
                            System.err.println("Warning: Preview rasterization skipped: " + e.getMessage());
                        }
                    }
                }

                String baseName = upload.filename != null ? upload.filename.replaceAll("(?i)\\.dxf$", "") : "drawing";

                if (upload.returnJson || "json".equalsIgnoreCase(upload.format)) {
                    String pdfB64 = Base64.getEncoder().encodeToString(pdfBytes);
                    String previewB64 = previewBytes != null ? Base64.getEncoder().encodeToString(previewBytes) : "";

                    String json = String.format(
                            "{\"success\":true,\"filename\":\"%s.pdf\",\"fileSizeBytes\":%d,\"pdfBase64\":\"%s\",\"previewBase64\":\"%s\"}",
                            escapeJson(baseName), pdfBytes.length, pdfB64, previewB64
                    );

                    byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                } else {
                    // Return raw PDF binary stream
                    Headers respHeaders = exchange.getResponseHeaders();
                    respHeaders.set("Content-Type", "application/pdf");
                    respHeaders.set("Content-Disposition", "attachment; filename=\"" + baseName + ".pdf\"");
                    respHeaders.set("Content-Length", String.valueOf(pdfBytes.length));
                    exchange.sendResponseHeaders(200, pdfBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(pdfBytes);
                    }
                }

            } catch (Exception e) {
                System.err.println("Conversion error: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "DXF Conversion failed: " + e.getMessage());
            }
        }
    }

    private static class PreviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed. Use POST.");
                return;
            }

            try {
                ParsedUpload upload = parseRequest(exchange);
                if (upload.dxfContent == null || upload.dxfContent.trim().isEmpty()) {
                    sendError(exchange, 400, "Missing DXF file content.");
                    return;
                }

                DynamicHatchRuleEngine hatchEngine = DynamicHatchRuleEngine.getDefault();
                if (upload.targetLayers != null && !upload.targetLayers.isEmpty()) {
                    hatchEngine = new DynamicHatchRuleEngine(upload.targetLayers);
                }

                byte[] pngBytes;
                try (PDDocument doc = DxfToPdfConverter.convertToPdfDocument(upload.dxfContent, hatchEngine, null)) {
                    pngBytes = renderPreviewImage(doc, upload.dpi);
                }

                Headers respHeaders = exchange.getResponseHeaders();
                respHeaders.set("Content-Type", "image/png");
                respHeaders.set("Content-Length", String.valueOf(pngBytes.length));
                exchange.sendResponseHeaders(200, pngBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(pngBytes);
                }

            } catch (Exception e) {
                System.err.println("Preview error: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "Preview generation failed: " + e.getMessage());
            }
        }
    }

    private static class SampleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            byte[] bytes = null;
            Path samplePath = Paths.get(STATIC_DIR_OVERRIDE, "sample.dxf");
            if (Files.exists(samplePath)) {
                bytes = Files.readAllBytes(samplePath);
            } else {
                try (InputStream is = DxfServer.class.getResourceAsStream("/static/sample.dxf")) {
                    if (is != null) bytes = readAllBytes(is);
                }
            }
            if (bytes == null) {
                bytes = generateSampleDxf().getBytes(StandardCharsets.UTF_8);
            }

            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("Content-Type", "application/dxf");
            respHeaders.set("Content-Disposition", "attachment; filename=\"sample_architectural_floorplan.dxf\"");
            respHeaders.set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            // Sanitize against directory traversal
            path = path.replace("\\", "/");
            if (path.contains("..")) {
                sendError(exchange, 403, "Access denied.");
                return;
            }

            byte[] content = null;
            String relativePath = path.startsWith("/") ? path.substring(1) : path;

            // 1. Try file system first (allows hot reloading during development)
            Path localPath = Paths.get(STATIC_DIR_OVERRIDE, relativePath);
            if (Files.exists(localPath) && !Files.isDirectory(localPath)) {
                content = Files.readAllBytes(localPath);
            } else {
                // Also check static/
                Path staticSub = Paths.get(STATIC_DIR_OVERRIDE, path);
                if (Files.exists(staticSub) && !Files.isDirectory(staticSub)) {
                    content = Files.readAllBytes(staticSub);
                }
            }

            // 2. If not found on disk, read from Classpath (when running inside JAR)
            if (content == null) {
                String resourcePath = "/static/" + relativePath;
                try (InputStream is = DxfServer.class.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        content = readAllBytes(is);
                    }
                }
            }

            if (content == null) {
                sendError(exchange, 404, "File not found: " + path);
                return;
            }

            String mimeType = getMimeType(path);
            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("Content-Type", mimeType);
            respHeaders.set("Content-Length", String.valueOf(content.length));
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    // =========================================================================
    // Helper Methods & Request Parser
    // =========================================================================

    private static class ParsedUpload {
        String dxfContent;
        String filename = "drawing.dxf";
        List<String> targetLayers = new ArrayList<>();
        boolean returnJson = false;
        String format = "pdf";
        int dpi = 150;
    }

    private static ParsedUpload parseRequest(HttpExchange exchange) throws IOException {
        ParsedUpload parsed = new ParsedUpload();
        Headers headers = exchange.getRequestHeaders();
        String contentType = headers.getFirst("Content-Type");
        String accept = headers.getFirst("Accept");
        String xFormat = headers.getFirst("X-Output-Format");
        String xFilename = headers.getFirst("X-Filename");
        String xLayers = headers.getFirst("X-Target-Layers");

        if (accept != null && accept.contains("application/json")) {
            parsed.returnJson = true;
        }
        if ("json".equalsIgnoreCase(xFormat)) {
            parsed.returnJson = true;
        }
        if (xFilename != null && !xFilename.trim().isEmpty()) {
            parsed.filename = xFilename.trim();
        }
        if (xLayers != null && !xLayers.trim().isEmpty()) {
            for (String l : xLayers.split(",")) {
                if (!l.trim().isEmpty()) parsed.targetLayers.add(l.trim());
            }
        }

        // Query parameters
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            Map<String, String> qMap = parseQuery(query);
            if (qMap.containsKey("filename")) parsed.filename = qMap.get("filename");
            if (qMap.containsKey("format") && "json".equalsIgnoreCase(qMap.get("format"))) parsed.returnJson = true;
            if (qMap.containsKey("dpi")) {
                try { parsed.dpi = Integer.parseInt(qMap.get("dpi")); } catch (Exception ignored) {}
            }
            if (qMap.containsKey("layers")) {
                for (String l : qMap.get("layers").split(",")) {
                    if (!l.trim().isEmpty()) parsed.targetLayers.add(l.trim());
                }
            }
        }

        InputStream is = exchange.getRequestBody();
        byte[] bodyBytes = readAllBytes(is);

        if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
            parseMultipart(bodyBytes, contentType, parsed);
        } else {
            // Raw text or binary
            parsed.dxfContent = new String(bodyBytes, StandardCharsets.UTF_8);
        }

        return parsed;
    }

    private static void parseMultipart(byte[] body, String contentType, ParsedUpload out) {
        String boundaryMarker = "--";
        int idx = contentType.toLowerCase().indexOf("boundary=");
        if (idx == -1) return;
        String boundary = boundaryMarker + contentType.substring(idx + 9).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }

        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
        List<Integer> positions = findOccurrences(body, boundaryBytes);

        for (int i = 0; i < positions.size() - 1; i++) {
            int start = positions.get(i) + boundaryBytes.length;
            int end = positions.get(i + 1);

            // Skip CRLF
            if (start + 2 <= end && body[start] == '\r' && body[start + 1] == '\n') {
                start += 2;
            }

            int headerEnd = findHeaderEnd(body, start, end);
            if (headerEnd == -1) continue;

            String headerText = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
            int dataStart = headerEnd + 4; // Skip \r\n\r\n
            int dataEnd = end;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            }

            if (headerText.contains("name=\"file\"") || headerText.contains("filename=")) {
                // Extract filename if present
                int fnIdx = headerText.indexOf("filename=\"");
                if (fnIdx != -1) {
                    int fnEnd = headerText.indexOf("\"", fnIdx + 10);
                    if (fnEnd != -1) {
                        out.filename = headerText.substring(fnIdx + 10, fnEnd);
                    }
                }
                out.dxfContent = new String(body, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8);
            } else if (headerText.contains("name=\"layers\"")) {
                String val = new String(body, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8).trim();
                for (String l : val.split(",")) {
                    if (!l.trim().isEmpty()) out.targetLayers.add(l.trim());
                }
            } else if (headerText.contains("name=\"format\"")) {
                out.format = new String(body, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8).trim();
                if ("json".equalsIgnoreCase(out.format)) out.returnJson = true;
            }
        }
    }

    private static List<Integer> findOccurrences(byte[] source, byte[] target) {
        List<Integer> list = new ArrayList<>();
        outer:
        for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) continue outer;
            }
            list.add(i);
        }
        return list;
    }

    private static int findHeaderEnd(byte[] data, int start, int end) {
        for (int i = start; i <= end - 4; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String val = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    map.put(key, val);
                } else if (!pair.isEmpty()) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (UnsupportedEncodingException ignored) {}
        }
        return map;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, X-Requested-With, X-Filename, X-Target-Layers, X-Output-Format");
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" + escapeJson(message) + "\",\"status\":" + status + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".dxf")) return "application/dxf";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static byte[] renderPreviewImage(PDDocument doc, int targetDpi) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        org.apache.pdfbox.pdmodel.common.PDRectangle cropBox = doc.getPage(0).getCropBox();
        float width = cropBox.getWidth();
        float height = cropBox.getHeight();
        // Adaptive resolution scaling: map large sheets to a crisp 1800px viewport
        float maxDim = (targetDpi > 200) ? 2400f : 1800f;
        float scale = Math.min(1.0f, maxDim / Math.max(width, height));
        BufferedImage img = renderer.renderImage(0, scale);
        ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", imgBaos);
        return imgBaos.toByteArray();
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[8192];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Generates a sample architectural CAD floorplan DXF with walls, rooms, hatches, and labels.
     */
    private static String generateSampleDxf() {
        return "0\nSECTION\n2\nHEADER\n0\nENDSEC\n" +
                "0\nSECTION\n2\nTABLES\n0\nTABLE\n2\nLAYER\n70\n4\n" +
                "0\nLAYER\n2\nWALLS\n70\n0\n62\n7\n6\nCONTINUOUS\n0\n" +
                "0\nLAYER\n2\nPARKING\n70\n0\n62\n4\n6\nCONTINUOUS\n0\n" +
                "0\nLAYER\n2\nROAD\n70\n0\n62\n8\n6\nCONTINUOUS\n0\n" +
                "0\nLAYER\n2\nTEXT\n70\n0\n62\n2\n6\nCONTINUOUS\n0\n" +
                "0\nLAYER\n2\nDOORS\n70\n0\n62\n1\n6\nCONTINUOUS\n0\n" +
                "0\nENDTAB\n0\nENDSEC\n" +
                "0\nSECTION\n2\nENTITIES\n" +
                // Outer Boundary
                "0\nLWPOLYLINE\n8\nWALLS\n90\n4\n70\n1\n10\n0.0\n20\n0.0\n10\n600.0\n20\n0.0\n10\n600.0\n20\n400.0\n10\n0.0\n20\n400.0\n" +
                // Interior Wall
                "0\nLINE\n8\nWALLS\n10\n300.0\n20\n0.0\n11\n300.0\n21\n400.0\n" +
                // Parking Area Hatch
                "0\nHATCH\n8\nPARKING\n2\nSOLID\n70\n1\n71\n0\n91\n1\n92\n3\n72\n0\n73\n1\n93\n4\n" +
                "10\n50.0\n20\n50.0\n10\n250.0\n20\n50.0\n10\n250.0\n20\n180.0\n10\n50.0\n20\n180.0\n" +
                // Road Area Hatch
                "0\nHATCH\n8\nROAD\n2\nANSI31\n70\n0\n71\n0\n91\n1\n92\n3\n72\n0\n73\n1\n93\n4\n" +
                "10\n350.0\n20\n50.0\n10\n550.0\n20\n50.0\n10\n550.0\n20\n350.0\n10\n350.0\n20\n350.0\n" +
                // Column Circles
                "0\nCIRCLE\n8\nWALLS\n10\n50.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n250.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n350.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n550.0\n20\n350.0\n40\n15.0\n" +
                // Door Arc
                "0\nARC\n8\nDOORS\n10\n300.0\n20\n200.0\n40\n40.0\n50\n0.0\n51\n90.0\n" +
                // Labels / Text
                "0\nTEXT\n8\nTEXT\n10\n90.0\n20\n110.0\n40\n18.0\n1\nVISITOR PARKING\n" +
                "0\nTEXT\n8\nTEXT\n10\n390.0\n20\n200.0\n40\n18.0\n1\nACCESS ROAD\n" +
                "0\nTEXT\n8\nTEXT\n10\n90.0\n20\n280.0\n40\n22.0\n1\nCOMMERCIAL OFFICE\n" +
                "0\nTEXT\n8\nTEXT\n10\n180.0\n20\n420.0\n40\n26.0\n1\nSAMPLE ARCHITECTURAL PLAN (CADCONVERT)\n" +
                "0\nENDSEC\n0\nEOF\n";
    }
}
