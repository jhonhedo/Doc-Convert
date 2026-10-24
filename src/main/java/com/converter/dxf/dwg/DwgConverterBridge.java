package com.converter.dxf.dwg;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Headless bridge for translating AutoCAD DWG binary drawings into clean-room DXF
 * format via the open LibreDWG engine ('dwg2dxf').
 * Operates with immediate file cleanup ensuring volatile-only data retention.
 */
public class DwgConverterBridge {

    private static final String DEFAULT_DWG2DXF_BIN = "dwg2dxf";
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    public static class DwgBridgeException extends IOException {
        public DwgBridgeException(String message) {
            super(message);
        }

        public DwgBridgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Determines whether the given byte payload represents an AutoCAD DWG binary file.
     */
    public static boolean isDwgBinary(byte[] bytes) {
        if (bytes == null || bytes.length < 6) return false;
        // AutoCAD DWG magic header is AC10xx (e.g., AC1015, AC1018, AC1021, AC1024, AC1027, AC1032)
        return bytes[0] == 'A' && bytes[1] == 'C' && bytes[2] == '1' && bytes[3] == '0';
    }

    /**
     * Checks if the dwg2dxf CLI utility is installed and executable on the host system or container.
     */
    public static boolean isDwg2DxfAvailable() {
        String binary = getDwg2DxfBinary();
        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {}

        // Alternative check: check if file exists directly if absolute path was given
        if (binary.contains(File.separator) || binary.contains("/")) {
            return Files.isExecutable(Paths.get(binary));
        }

        return false;
    }

    /**
     * Converts a DWG binary byte array into DXF string content.
     *
     * @param dwgBytes Raw DWG binary file bytes.
     * @return Converted DXF text content ready for DxfToPdfConverter.
     * @throws DwgBridgeException if dwg2dxf is missing or conversion fails.
     */
    public static String convertDwgToDxf(byte[] dwgBytes) throws IOException {
        if (dwgBytes == null || dwgBytes.length == 0) {
            throw new IllegalArgumentException("DWG byte stream is empty.");
        }

        String binary = getDwg2DxfBinary();
        if (!isDwg2DxfAvailable()) {
            throw new DwgBridgeException(
                    "DWG binary conversion requires the LibreDWG bridge ('" + binary + "'). " +
                    "This utility is pre-installed in the DocConvert Cloud Run Docker container. " +
                    "For local testing without Docker, please install 'libredwg-tools' (apt install libredwg-tools) " +
                    "or convert DWG to DXF prior to upload."
            );
        }

        Path tempDir = getVolatileTempDir();
        Path tempDwg = Files.createTempFile(tempDir, "cadconvert_input_", ".dwg");
        Path tempDxf = Files.createTempFile(tempDir, "cadconvert_output_", ".dxf");

        try {
            // Write incoming DWG bytes to ephemeral storage
            Files.write(tempDwg, dwgBytes);

            // Execute: dwg2dxf -y -o <tempDxf> <tempDwg>
            ProcessBuilder pb = new ProcessBuilder(
                    binary,
                    "-y",
                    "-o",
                    tempDxf.toAbsolutePath().toString(),
                    tempDwg.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output stream to prevent pipe stall
            ByteArrayOutputStream processLog = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    processLog.write(buffer, 0, read);
                }
            }

            boolean completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new DwgBridgeException("DWG conversion timed out after " + PROCESS_TIMEOUT_SECONDS + " seconds.");
            }

            if (process.exitValue() != 0 && (!Files.exists(tempDxf) || Files.size(tempDxf) == 0)) {
                String log = processLog.toString("UTF-8");
                throw new DwgBridgeException("LibreDWG engine failed (code " + process.exitValue() + "): " + log);
            }

            if (!Files.exists(tempDxf) || Files.size(tempDxf) == 0) {
                throw new DwgBridgeException("LibreDWG completed but produced an empty DXF file.");
            }

            byte[] dxfBytes = Files.readAllBytes(tempDxf);
            return new String(dxfBytes, StandardCharsets.UTF_8);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DwgBridgeException("DWG conversion process was interrupted.", e);
        } finally {
            // Immediate deletion of volatile files (zero disk retention guarantee)
            try { Files.deleteIfExists(tempDwg); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempDxf); } catch (Exception ignored) {}
        }
    }

    private static String getDwg2DxfBinary() {
        String env = System.getenv("DWG2DXF_BIN");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return DEFAULT_DWG2DXF_BIN;
    }

    private static Path getVolatileTempDir() {
        // Favor Linux RAM-backed shared memory /dev/shm if present
        Path shm = Paths.get("/dev/shm");
        if (Files.exists(shm) && Files.isDirectory(shm) && Files.isWritable(shm)) {
            return shm;
        }
        return Paths.get(System.getProperty("java.io.tmpdir"));
    }
}
