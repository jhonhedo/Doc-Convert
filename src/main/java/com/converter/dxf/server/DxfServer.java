package com.converter.dxf.server;

import com.converter.dxf.DxfToPdfConverter;
import com.converter.dxf.dwg.DwgConverterBridge;
import com.converter.dxf.pdf.PdfCompressService;
import com.converter.dxf.pdf.PdfMergeService;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Production-ready HTTP server providing REST API and DocConvert Web UI
 * for the CAD & PDF Vector Utility Suite:
 * - DXF to Vector PDF Converter
 * - DWG to Vector PDF Converter (via LibreDWG bridge)
 * - Vector-Safe PDF Merge Utility
 * - Multi-tier PDF Compression Engine
 */
public class DxfServer {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final long START_TIME = System.currentTimeMillis();
    private static final String STATIC_DIR_OVERRIDE = "src/main/resources/static";

    // Configurable Upload & Concurrency Limits
    private static final int DEFAULT_MAX_UPLOAD_MB = 25;
    private static final int MAX_UPLOAD_BYTES = initMaxUploadBytes();
    private static final int MAX_CONCURRENT_CONVERSIONS = initMaxConversions();
    private static final Semaphore CONVERSION_SEMAPHORE = new Semaphore(MAX_CONCURRENT_CONVERSIONS);

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

    private static int initMaxUploadBytes() {
        String env = System.getenv("MAX_UPLOAD_SIZE_MB");
        int mb = DEFAULT_MAX_UPLOAD_MB;
        if (env != null && !env.trim().isEmpty()) {
            try { mb = Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        return mb * 1024 * 1024;
    }

    private static int initMaxConversions() {
        String env = System.getenv("MAX_CONCURRENT_CONVERSIONS");
        int max = 3;
        if (env != null && !env.trim().isEmpty()) {
            try { max = Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        return Math.max(1, max);
    }

    private void setupRoutes() {
        // Health check endpoint
        server.createContext("/api/health", new HealthHandler());

        // CAD to PDF Conversion endpoints
        server.createContext("/api/convert", new ConvertHandler());
        server.createContext("/api/convert/dwg", new ConvertHandler());

        // Preview endpoint (converts & renders Page 1 to PNG thumbnail)
        server.createContext("/api/preview", new PreviewHandler());

        // PDF Utility Endpoints
        server.createContext("/api/pdf/merge", new PdfMergeHandler());
        server.createContext("/api/pdf/compress", new PdfCompressHandler());

        // Sample DXF download endpoint
        server.createContext("/api/sample", new SampleHandler());

        // Static files & clean SEO URLs handler (root and /static/*)
        server.createContext("/", new StaticFileHandler());
    }

    public void start() {
        server.start();
        System.out.println("=================================================================");
        System.out.println("   CADConvert / DocConvert - CAD & PDF Multi-Tool Utility Suite");
        System.out.println("=================================================================");
        System.out.println(" Server running at:     http://" + host + ":" + port + "/");
        System.out.println(" CAD Convert API:       http://" + host + ":" + port + "/api/convert");
        System.out.println(" DWG Convert API:       http://" + host + ":" + port + "/api/convert/dwg");
        System.out.println(" PDF Merge API:         http://" + host + ":" + port + "/api/pdf/merge");
        System.out.println(" PDF Compress API:      http://" + host + ":" + port + "/api/pdf/compress");
        System.out.println(" Health Check:          http://" + host + ":" + port + "/api/health");
        System.out.println(" Max Upload Limit:      " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB");
        System.out.println(" Max Concurrency:       " + MAX_CONCURRENT_CONVERSIONS + " workers");
        System.out.println(" LibreDWG CLI Bridge:   " + (DwgConverterBridge.isDwg2DxfAvailable() ? "AVAILABLE" : "CONTAINER-ONLY"));
        System.out.println(" Storage Policy:        100% In-Memory (Zero Disk Retention)");
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
            System.err.println("Fatal: Failed to start server: " + e.getMessage());
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
            boolean dwgBridge = DwgConverterBridge.isDwg2DxfAvailable();
            Runtime rt = Runtime.getRuntime();
            long freeMem = rt.freeMemory() / (1024 * 1024);
            long totalMem = rt.totalMemory() / (1024 * 1024);

            String json = String.format(
                    "{\"status\":\"UP\",\"service\":\"CADConvert Multi-Tool Suite\",\"version\":\"1.2.0\"," +
                    "\"uptimeSeconds\":%d,\"maxUploadMb\":%d,\"dwgBridgeAvailable\":%b," +
                    "\"jvmMemoryMb\":{\"free\":%d,\"total\":%d},\"tools\":[\"dxf-to-pdf\",\"dwg-to-pdf\",\"merge-pdf\",\"compress-pdf\"]}",
                    uptimeSec, (MAX_UPLOAD_BYTES / (1024 * 1024)), dwgBridge, freeMem, totalMem
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
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed. Use POST.");
                return;
            }

            String reqId = UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();
            ParsedUpload upload;

            try {
                upload = parseRequest(exchange);
            } catch (PayloadTooLargeException e) {
                logOp("CONVERT", reqId, 413, System.currentTimeMillis() - startTime, 0, 0, "PayloadTooLarge");
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
                return;
            } catch (Exception e) {
                logOp("CONVERT", reqId, 400, System.currentTimeMillis() - startTime, 0, 0, "BadRequest");
                sendError(exchange, 400, "BAD_REQUEST", "Unable to parse request body.");
                return;
            }

            // DWG binary conversion handling
            boolean isDwg = (upload.filename != null && upload.filename.toLowerCase().endsWith(".dwg"))
                    || exchange.getRequestURI().getPath().endsWith("/dwg")
                    || DwgConverterBridge.isDwgBinary(upload.binaryData);

            if (isDwg) {
                if (upload.binaryData == null || upload.binaryData.length == 0) {
                    sendError(exchange, 400, "EMPTY_INPUT", "Missing DWG file binary content.");
                    return;
                }
                try {
                    upload.dxfContent = DwgConverterBridge.convertDwgToDxf(upload.binaryData);
                } catch (DwgConverterBridge.DwgBridgeException e) {
                    logOp("CONVERT_DWG", reqId, 422, System.currentTimeMillis() - startTime, upload.binaryData.length, 0, "DwgBridgeFailed");
                    sendError(exchange, 422, "DWG_CONVERSION_UNAVAILABLE", e.getMessage());
                    return;
                }
            }

            if (upload.dxfContent == null || upload.dxfContent.trim().isEmpty()) {
                logOp("CONVERT", reqId, 400, System.currentTimeMillis() - startTime, 0, 0, "EmptyContent");
                sendError(exchange, 400, "EMPTY_INPUT", "Missing DXF or DWG file content in request body.");
                return;
            }

            if (!isValidDxfContent(upload.dxfContent)) {
                logOp("CONVERT", reqId, 400, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "InvalidDxfHeader");
                sendError(exchange, 400, "INVALID_DXF", "The provided file is not a valid DXF drawing. Expected standard DXF section headers.");
                return;
            }

            boolean acquired = false;
            try {
                acquired = CONVERSION_SEMAPHORE.tryAcquire(5, TimeUnit.SECONDS);
                if (!acquired) {
                    logOp("CONVERT", reqId, 429, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "ConcurrencyLimitReached");
                    sendError(exchange, 429, "RATE_LIMITED", "Server is busy processing maximum concurrent drawings. Please retry in a few moments.");
                    return;
                }

                // Setup Hatch Engine (disabled by default, enabled only if explicitly selected)
                DynamicHatchRuleEngine hatchEngine = resolveHatchEngine(upload);

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
                            previewBytes = renderPreviewImage(doc, Math.min(upload.dpi, 300));
                        } catch (Exception ignored) {}
                    }
                }

                String baseName = upload.filename != null ? upload.filename.replaceAll("(?i)\\.(dxf|dwg)$", "") : "drawing";
                baseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

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
                    logOp("CONVERT", reqId, 200, System.currentTimeMillis() - startTime, upload.dxfContent.length(), pdfBytes.length, "JSON");
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
                    logOp("CONVERT", reqId, 200, System.currentTimeMillis() - startTime, upload.dxfContent.length(), pdfBytes.length, "RAW_PDF");
                }

            } catch (Exception e) {
                logOp("CONVERT", reqId, 500, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "Error=" + e.getClass().getSimpleName());
                sendError(exchange, 500, "CONVERSION_FAILED", "Failed to compile CAD drawing into vector PDF: " + e.getMessage());
            } finally {
                if (acquired) {
                    CONVERSION_SEMAPHORE.release();
                }
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
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed. Use POST.");
                return;
            }

            String reqId = UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();
            ParsedUpload upload;

            try {
                upload = parseRequest(exchange);
            } catch (PayloadTooLargeException e) {
                logOp("PREVIEW", reqId, 413, System.currentTimeMillis() - startTime, 0, 0, "PayloadTooLarge");
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
                return;
            } catch (Exception e) {
                logOp("PREVIEW", reqId, 400, System.currentTimeMillis() - startTime, 0, 0, "BadRequest");
                sendError(exchange, 400, "BAD_REQUEST", "Unable to parse request body.");
                return;
            }

            // DWG handling
            boolean isDwg = (upload.filename != null && upload.filename.toLowerCase().endsWith(".dwg"))
                    || DwgConverterBridge.isDwgBinary(upload.binaryData);

            if (isDwg) {
                try {
                    upload.dxfContent = DwgConverterBridge.convertDwgToDxf(upload.binaryData);
                } catch (DwgConverterBridge.DwgBridgeException e) {
                    sendError(exchange, 422, "DWG_CONVERSION_UNAVAILABLE", e.getMessage());
                    return;
                }
            }

            if (upload.dxfContent == null || upload.dxfContent.trim().isEmpty()) {
                logOp("PREVIEW", reqId, 400, System.currentTimeMillis() - startTime, 0, 0, "EmptyContent");
                sendError(exchange, 400, "EMPTY_INPUT", "Missing DXF or DWG file content.");
                return;
            }

            if (!isValidDxfContent(upload.dxfContent)) {
                logOp("PREVIEW", reqId, 400, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "InvalidDxfHeader");
                sendError(exchange, 400, "INVALID_DXF", "The provided file is not a valid DXF drawing.");
                return;
            }

            boolean acquired = false;
            try {
                acquired = CONVERSION_SEMAPHORE.tryAcquire(5, TimeUnit.SECONDS);
                if (!acquired) {
                    logOp("PREVIEW", reqId, 429, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "ConcurrencyLimitReached");
                    sendError(exchange, 429, "RATE_LIMITED", "Server is busy processing maximum concurrent drawings. Please retry in a few moments.");
                    return;
                }

                DynamicHatchRuleEngine hatchEngine = resolveHatchEngine(upload);

                byte[] pngBytes;
                try (PDDocument doc = DxfToPdfConverter.convertToPdfDocument(upload.dxfContent, hatchEngine, null)) {
                    pngBytes = renderPreviewImage(doc, Math.min(upload.dpi, 300));
                }

                Headers respHeaders = exchange.getResponseHeaders();
                respHeaders.set("Content-Type", "image/png");
                respHeaders.set("Content-Length", String.valueOf(pngBytes.length));
                exchange.sendResponseHeaders(200, pngBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(pngBytes);
                }
                logOp("PREVIEW", reqId, 200, System.currentTimeMillis() - startTime, upload.dxfContent.length(), pngBytes.length, "OK");

            } catch (Exception e) {
                logOp("PREVIEW", reqId, 500, System.currentTimeMillis() - startTime, upload.dxfContent.length(), 0, "Error=" + e.getClass().getSimpleName());
                sendError(exchange, 500, "PREVIEW_FAILED", "Preview generation failed: " + e.getMessage());
            } finally {
                if (acquired) {
                    CONVERSION_SEMAPHORE.release();
                }
            }
        }
    }

    /**
     * Handler for PDF Merging (POST /api/pdf/merge).
     * Accepts 2 or more PDF documents in multipart/form-data, merges them in volatile memory,
     * and streams back the unified PDF.
     */
    private static class PdfMergeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed. Use POST.");
                return;
            }

            String reqId = UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();
            ParsedUpload upload;

            try {
                upload = parseRequest(exchange);
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
                return;
            } catch (Exception e) {
                sendError(exchange, 400, "BAD_REQUEST", "Unable to parse request body.");
                return;
            }

            List<byte[]> pdfList = new ArrayList<>();
            for (UploadedFile file : upload.files) {
                if (file.data != null && file.data.length > 0) {
                    pdfList.add(file.data);
                }
            }

            if (pdfList.size() < 2) {
                sendError(exchange, 400, "INSUFFICIENT_FILES", "Merging requires at least 2 PDF documents. Received " + pdfList.size() + ".");
                return;
            }

            try {
                PdfMergeService.MergeResult result = PdfMergeService.merge(pdfList);
                byte[] mergedBytes = result.getPdfBytes();
                String outFilename = upload.filename != null && upload.filename.toLowerCase().endsWith(".pdf")
                        ? upload.filename : "merged_documents.pdf";

                if (upload.returnJson || "json".equalsIgnoreCase(upload.format)) {
                    String b64 = Base64.getEncoder().encodeToString(mergedBytes);
                    String json = String.format(
                            "{\"success\":true,\"filename\":\"%s\",\"totalPages\":%d,\"sourceFilesCount\":%d,\"fileSizeBytes\":%d,\"pdfBase64\":\"%s\"}",
                            escapeJson(outFilename), result.getTotalPages(), result.getSourceFilesCount(), mergedBytes.length, b64
                    );

                    byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
                } else {
                    Headers headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "application/pdf");
                    headers.set("Content-Disposition", "attachment; filename=\"" + escapeJson(outFilename) + "\"");
                    headers.set("Content-Length", String.valueOf(mergedBytes.length));
                    headers.set("X-Total-Pages", String.valueOf(result.getTotalPages()));
                    headers.set("X-Source-Files", String.valueOf(result.getSourceFilesCount()));
                    exchange.sendResponseHeaders(200, mergedBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(mergedBytes); }
                }

                logOp("MERGE_PDF", reqId, 200, System.currentTimeMillis() - startTime, 0, mergedBytes.length, "Files=" + pdfList.size());
            } catch (IllegalArgumentException | IOException e) {
                logOp("MERGE_PDF", reqId, 400, System.currentTimeMillis() - startTime, 0, 0, "InvalidInput=" + e.getMessage());
                sendError(exchange, 400, "INVALID_PDF", e.getMessage());
            } catch (Exception e) {
                logOp("MERGE_PDF", reqId, 500, System.currentTimeMillis() - startTime, 0, 0, "Error=" + e.getMessage());
                sendError(exchange, 500, "MERGE_FAILED", "Failed to merge PDF documents: " + e.getMessage());
            }
        }
    }

    /**
     * Handler for PDF Compression (POST /api/pdf/compress).
     * Accepts a PDF document and a compression level query parameter (low, medium, high),
     * applies in-memory raster downsampling and Flate recompression, and streams back the result.
     */
    private static class PdfCompressHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed. Use POST.");
                return;
            }

            String reqId = UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();
            ParsedUpload upload;

            try {
                upload = parseRequest(exchange);
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
                return;
            } catch (Exception e) {
                sendError(exchange, 400, "BAD_REQUEST", "Unable to parse request body.");
                return;
            }

            byte[] pdfBytes = upload.binaryData;
            if (pdfBytes == null || pdfBytes.length == 0) {
                if (!upload.files.isEmpty() && upload.files.get(0).data != null) {
                    pdfBytes = upload.files.get(0).data;
                    upload.filename = upload.files.get(0).filename;
                }
            }

            if (pdfBytes == null || pdfBytes.length == 0) {
                sendError(exchange, 400, "EMPTY_INPUT", "Missing PDF document content to compress.");
                return;
            }

            PdfCompressService.CompressionLevel level = PdfCompressService.CompressionLevel.fromString(upload.compressLevel);

            try {
                PdfCompressService.CompressResult result = PdfCompressService.compress(pdfBytes, level);
                byte[] outBytes = result.getPdfBytes();
                String outName = upload.filename != null ? upload.filename.replaceAll("(?i)\\.pdf$", "") + "_compressed.pdf" : "compressed.pdf";

                if (upload.returnJson || "json".equalsIgnoreCase(upload.format)) {
                    String b64 = Base64.getEncoder().encodeToString(outBytes);
                    String json = String.format(Locale.US,
                            "{\"success\":true,\"filename\":\"%s\",\"originalSize\":%d,\"compressedSize\":%d," +
                            "\"savingsPercentage\":%.1f,\"totalPages\":%d,\"optimizedImages\":%d,\"pdfBase64\":\"%s\"}",
                            escapeJson(outName), result.getOriginalSize(), result.getCompressedSize(),
                            result.getSavingsPercentage(), result.getTotalPages(), result.getOptimizedImagesCount(), b64
                    );

                    byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
                } else {
                    Headers headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "application/pdf");
                    headers.set("Content-Disposition", "attachment; filename=\"" + escapeJson(outName) + "\"");
                    headers.set("Content-Length", String.valueOf(outBytes.length));
                    headers.set("X-Original-Size", String.valueOf(result.getOriginalSize()));
                    headers.set("X-Compressed-Size", String.valueOf(result.getCompressedSize()));
                    headers.set("X-Savings-Percent", String.format(Locale.US, "%.1f", result.getSavingsPercentage()));
                    exchange.sendResponseHeaders(200, outBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(outBytes); }
                }

                logOp("COMPRESS_PDF", reqId, 200, System.currentTimeMillis() - startTime, result.getOriginalSize(), result.getCompressedSize(), "Saved=" + String.format(Locale.US, "%.1f%%", result.getSavingsPercentage()));
            } catch (IllegalArgumentException | IOException e) {
                logOp("COMPRESS_PDF", reqId, 400, System.currentTimeMillis() - startTime, pdfBytes.length, 0, "InvalidInput=" + e.getMessage());
                sendError(exchange, 400, "INVALID_PDF", e.getMessage());
            } catch (Exception e) {
                logOp("COMPRESS_PDF", reqId, 500, System.currentTimeMillis() - startTime, pdfBytes.length, 0, "Error=" + e.getMessage());
                sendError(exchange, 500, "COMPRESSION_FAILED", "Failed to compress PDF: " + e.getMessage());
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
                    if (is != null) bytes = readAllBytesBounded(is, 1024 * 1024);
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
                sendError(exchange, 403, "ACCESS_DENIED", "Access denied.");
                return;
            }

            byte[] content = null;
            String relativePath = path.startsWith("/") ? path.substring(1) : path;

            // Handle clean SEO URLs without extensions (e.g., /merge-pdf -> merge-pdf.html)
            List<String> candidatePaths = new ArrayList<>();
            candidatePaths.add(relativePath);
            if (!relativePath.contains(".")) {
                candidatePaths.add(relativePath + ".html");
            }

            for (String cand : candidatePaths) {
                // 1. Try file system first (for local live edits)
                Path localPath = Paths.get(STATIC_DIR_OVERRIDE, cand);
                if (Files.exists(localPath) && !Files.isDirectory(localPath)) {
                    content = Files.readAllBytes(localPath);
                    path = cand;
                    break;
                }

                // 2. Classpath resource
                String resourcePath = "/static/" + cand;
                try (InputStream is = DxfServer.class.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        content = readAllBytesBounded(is, 10 * 1024 * 1024);
                        path = cand;
                        break;
                    }
                }
            }

            if (content == null) {
                sendError(exchange, 404, "NOT_FOUND", "File not found: " + path);
                return;
            }

            String mimeType = getMimeType(path);
            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("Content-Type", mimeType);
            respHeaders.set("Content-Length", String.valueOf(content.length));
            respHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate");
            respHeaders.set("Pragma", "no-cache");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    // =========================================================================
    // Helper Methods & Request Parser
    // =========================================================================

    private static DynamicHatchRuleEngine resolveHatchEngine(ParsedUpload upload) {
        if ("all".equalsIgnoreCase(upload.hatchMode)) {
            DynamicHatchRuleEngine engine = new DynamicHatchRuleEngine(true);
            engine.setEnabled(true);
            engine.setForceSolidAll(true);
            return engine;
        } else if ("default".equalsIgnoreCase(upload.hatchMode) || "enabled".equalsIgnoreCase(upload.hatchMode) || "true".equalsIgnoreCase(upload.hatchMode)) {
            DynamicHatchRuleEngine engine;
            if (upload.targetLayers != null && !upload.targetLayers.isEmpty()) {
                engine = new DynamicHatchRuleEngine(upload.targetLayers);
            } else {
                engine = DynamicHatchRuleEngine.getDefault();
            }
            engine.setEnabled(true);
            return engine;
        } else {
            // Disabled by default (upload.hatchMode is "none" or omitted)
            return DynamicHatchRuleEngine.getDisabledEngine();
        }
    }

    private static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    public static class UploadedFile {
        public String filename = "file";
        public byte[] data = new byte[0];

        public UploadedFile(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }
    }

    private static class ParsedUpload {
        String dxfContent;
        byte[] binaryData;
        String filename = "drawing.dxf";
        List<String> targetLayers = new ArrayList<>();
        List<UploadedFile> files = new ArrayList<>();
        boolean returnJson = false;
        String format = "pdf";
        String compressLevel = "medium";
        String hatchMode = "none"; // Disabled by default
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
        String xLevel = headers.getFirst("X-Compression-Level");
        String xHatchMode = headers.getFirst("X-Hatch-Mode");

        if (xHatchMode != null && !xHatchMode.trim().isEmpty()) {
            parsed.hatchMode = xHatchMode.trim();
        }

        if (accept != null && accept.contains("application/json")) {
            parsed.returnJson = true;
        }
        if ("json".equalsIgnoreCase(xFormat)) {
            parsed.returnJson = true;
        }
        if (xFilename != null && !xFilename.trim().isEmpty()) {
            parsed.filename = xFilename.trim();
        }
        if (xLevel != null && !xLevel.trim().isEmpty()) {
            parsed.compressLevel = xLevel.trim();
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
            if (qMap.containsKey("level")) parsed.compressLevel = qMap.get("level");
            if (qMap.containsKey("hatchMode")) parsed.hatchMode = qMap.get("hatchMode");
            if (qMap.containsKey("hatch")) parsed.hatchMode = qMap.get("hatch");
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
        byte[] bodyBytes = readAllBytesBounded(is, MAX_UPLOAD_BYTES);

        if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
            parseMultipart(bodyBytes, contentType, parsed);
        } else {
            // Raw text or binary
            parsed.binaryData = bodyBytes;
            parsed.dxfContent = new String(bodyBytes, StandardCharsets.UTF_8);
        }

        return parsed;
    }

    private static boolean isValidDxfContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.startsWith("AutoCAD Binary DXF")) {
            return true;
        }
        String head = content.length() > 4096 ? content.substring(0, 4096) : content;
        return head.contains("SECTION") || head.contains("ENTITIES") || head.contains("HEADER") || head.contains("BLOCKS") || head.contains("TABLES") || content.contains("EOF");
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

            byte[] partBytes = Arrays.copyOfRange(body, dataStart, dataEnd);

            if (headerText.contains("filename=") || headerText.contains("name=\"file\"") || headerText.contains("name=\"files\"")) {
                String partFilename = "upload";
                int fnIdx = headerText.indexOf("filename=\"");
                if (fnIdx != -1) {
                    int fnEnd = headerText.indexOf("\"", fnIdx + 10);
                    if (fnEnd != -1) {
                        partFilename = headerText.substring(fnIdx + 10, fnEnd);
                    }
                }

                out.files.add(new UploadedFile(partFilename, partBytes));

                // First file becomes default primary
                if (out.binaryData == null) {
                    out.binaryData = partBytes;
                    out.filename = partFilename;
                    out.dxfContent = new String(partBytes, StandardCharsets.UTF_8);
                }
            } else if (headerText.contains("name=\"layers\"")) {
                String val = new String(partBytes, StandardCharsets.UTF_8).trim();
                for (String l : val.split(",")) {
                    if (!l.trim().isEmpty()) out.targetLayers.add(l.trim());
                }
            } else if (headerText.contains("name=\"format\"")) {
                out.format = new String(partBytes, StandardCharsets.UTF_8).trim();
                if ("json".equalsIgnoreCase(out.format)) out.returnJson = true;
            } else if (headerText.contains("name=\"level\"")) {
                out.compressLevel = new String(partBytes, StandardCharsets.UTF_8).trim();
            } else if (headerText.contains("name=\"hatchMode\"") || headerText.contains("name=\"hatch\"")) {
                out.hatchMode = new String(partBytes, StandardCharsets.UTF_8).trim();
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
        h.set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, X-Requested-With, X-Filename, X-Target-Layers, X-Output-Format, X-Compression-Level");
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        String json = "{\"error\":\"" + escapeJson(message) + "\",\"code\":\"" + escapeJson(code) + "\",\"status\":" + status + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void logOp(String op, String reqId, int status, long durationMs, long inBytes, long outBytes, String note) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        System.out.println(String.format(
            "[%s] [OP=%s] [REQ=%s] [STATUS=%d] [DURATION_MS=%d] [IN_BYTES=%d] [OUT_BYTES=%d]%s",
            sdf.format(new Date()),
            op,
            reqId,
            status,
            durationMs,
            inBytes,
            outBytes,
            (note != null && !note.isEmpty()) ? " " + note : ""
        ));
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
        if (lower.endsWith(".dwg")) return "application/acad";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".xml")) return "application/xml; charset=UTF-8";
        if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }

    private static byte[] renderPreviewImage(PDDocument doc, int targetDpi) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        org.apache.pdfbox.pdmodel.common.PDRectangle cropBox = doc.getPage(0).getCropBox();
        float width = cropBox.getWidth();
        float height = cropBox.getHeight();
        float maxDim = (targetDpi > 200) ? 2400f : 1800f;
        float scale = Math.min(1.0f, maxDim / Math.max(width, height));
        BufferedImage img = renderer.renderImage(0, scale);
        ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", imgBaos);
        return imgBaos.toByteArray();
    }

    private static byte[] readAllBytesBounded(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        int total = 0;
        byte[] data = new byte[8192];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            total += nRead;
            if (total > maxBytes) {
                throw new PayloadTooLargeException("Upload size exceeds maximum allowed limit of " + (maxBytes / (1024 * 1024)) + " MB.");
            }
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
                "0\nLWPOLYLINE\n8\nWALLS\n90\n4\n70\n1\n10\n0.0\n20\n0.0\n10\n600.0\n20\n0.0\n10\n600.0\n20\n400.0\n10\n0.0\n20\n400.0\n" +
                "0\nLINE\n8\nWALLS\n10\n300.0\n20\n0.0\n11\n300.0\n21\n400.0\n" +
                "0\nHATCH\n8\nPARKING\n2\nSOLID\n70\n1\n71\n0\n91\n1\n92\n3\n72\n0\n73\n1\n93\n4\n" +
                "10\n50.0\n20\n50.0\n10\n250.0\n20\n50.0\n10\n250.0\n20\n180.0\n10\n50.0\n20\n180.0\n" +
                "0\nHATCH\n8\nROAD\n2\nANSI31\n70\n0\n71\n0\n91\n1\n92\n3\n72\n0\n73\n1\n93\n4\n" +
                "10\n350.0\n20\n50.0\n10\n550.0\n20\n50.0\n10\n550.0\n20\n350.0\n10\n350.0\n20\n350.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n50.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n250.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n350.0\n20\n350.0\n40\n15.0\n" +
                "0\nCIRCLE\n8\nWALLS\n10\n550.0\n20\n350.0\n40\n15.0\n" +
                "0\nARC\n8\nDOORS\n10\n300.0\n20\n200.0\n40\n40.0\n50\n0.0\n51\n90.0\n" +
                "0\nTEXT\n8\nTEXT\n10\n90.0\n20\n110.0\n40\n18.0\n1\nVISITOR PARKING\n" +
                "0\nTEXT\n8\nTEXT\n10\n390.0\n20\n200.0\n40\n18.0\n1\nACCESS ROAD\n" +
                "0\nTEXT\n8\nTEXT\n10\n90.0\n20\n280.0\n40\n22.0\n1\nCOMMERCIAL OFFICE\n" +
                "0\nTEXT\n8\nTEXT\n10\n180.0\n20\n420.0\n40\n26.0\n1\nSAMPLE ARCHITECTURAL PLAN (DOCCONVERT)\n" +
                "0\nENDSEC\n0\nEOF\n";
    }
}
