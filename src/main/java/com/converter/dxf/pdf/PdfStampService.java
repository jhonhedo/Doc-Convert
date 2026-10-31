package com.converter.dxf.pdf;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * In-memory PDF Drawing Set Stamping & Watermarking Service.
 * Imprints crisp, professional engineering text stamps ("FOR APPROVAL", "ISSUED FOR CONSTRUCTION", dates, sheet tags)
 * with precise opacity, positioning, and dimensioning.
 * 100% Vector Text & Linework with zero disk retention.
 */
public class PdfStampService {

    public enum StampPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER,
        DIAGONAL;

        public static StampPosition fromString(String val) {
            if (val == null || val.trim().isEmpty()) return DIAGONAL;
            String lower = val.trim().toLowerCase().replace("-", "_").replace(" ", "_");
            if (lower.contains("top_left")) return TOP_LEFT;
            if (lower.contains("top_right")) return TOP_RIGHT;
            if (lower.contains("bottom_left")) return BOTTOM_LEFT;
            if (lower.contains("bottom_right")) return BOTTOM_RIGHT;
            if (lower.contains("center")) return CENTER;
            if (lower.contains("diagonal")) return DIAGONAL;
            return DIAGONAL;
        }
    }

    public static class StampOptions {
        private String text = "FOR APPROVAL";
        private StampPosition position = StampPosition.DIAGONAL;
        private float opacity = 0.5f;
        private float fontSize = 36f;
        private Color color = new Color(220, 38, 38); // Engineering red
        private boolean drawBorder = true;
        private String pageRangeExpr = "all";

        public StampOptions() {}

        public StampOptions setText(String text) {
            if (text != null && !text.trim().isEmpty()) this.text = text.trim();
            return this;
        }

        public StampOptions setPosition(StampPosition position) {
            if (position != null) this.position = position;
            return this;
        }

        public StampOptions setOpacity(float opacity) {
            this.opacity = Math.max(0.05f, Math.min(1.0f, opacity));
            return this;
        }

        public StampOptions setFontSize(float fontSize) {
            this.fontSize = Math.max(8f, Math.min(160f, fontSize));
            return this;
        }

        public StampOptions setColor(Color color) {
            if (color != null) this.color = color;
            return this;
        }

        public StampOptions setColorHex(String hex) {
            if (hex != null && !hex.trim().isEmpty()) {
                try {
                    String clean = hex.trim();
                    if (!clean.startsWith("#")) clean = "#" + clean;
                    this.color = Color.decode(clean);
                } catch (Exception ignored) {}
            }
            return this;
        }

        public StampOptions setDrawBorder(boolean drawBorder) {
            this.drawBorder = drawBorder;
            return this;
        }

        public StampOptions setPageRangeExpr(String pageRangeExpr) {
            if (pageRangeExpr != null) this.pageRangeExpr = pageRangeExpr;
            return this;
        }

        public String getText() { return text; }
        public StampPosition getPosition() { return position; }
        public float getOpacity() { return opacity; }
        public float getFontSize() { return fontSize; }
        public Color getColor() { return color; }
        public boolean isDrawBorder() { return drawBorder; }
        public String getPageRangeExpr() { return pageRangeExpr; }
    }

    public static class StampResult {
        private final byte[] pdfBytes;
        private final String filename;
        private final int totalPages;
        private final int stampedPagesCount;
        private final String appliedStampText;

        public StampResult(byte[] pdfBytes, String filename, int totalPages, int stampedPagesCount, String appliedStampText) {
            this.pdfBytes = pdfBytes;
            this.filename = filename;
            this.totalPages = totalPages;
            this.stampedPagesCount = stampedPagesCount;
            this.appliedStampText = appliedStampText;
        }

        public byte[] getPdfBytes() { return pdfBytes; }
        public String getFilename() { return filename; }
        public int getTotalPages() { return totalPages; }
        public int getStampedPagesCount() { return stampedPagesCount; }
        public String getAppliedStampText() { return appliedStampText; }
    }

    /**
     * Imprints vector stamp on specified pages of a PDF in memory.
     */
    public static StampResult stamp(byte[] pdfBytes, StampOptions options, String sourceFilename) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF document content is empty.");
        }

        if (!isValidPdfHeader(pdfBytes)) {
            throw new IOException("The provided file is not a valid PDF document (missing %PDF- header).");
        }

        if (options == null) options = new StampOptions();
        String baseName = sanitizeBaseName(sourceFilename);

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) {
                throw new IOException("PDF document contains no pages.");
            }

            List<Integer> pagesToStamp = PdfSplitService.parsePageRange(options.getPageRangeExpr(), totalPages);
            Set<Integer> stampSet = new HashSet<>(pagesToStamp);
            PDFont font = PDType1Font.HELVETICA_BOLD;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            String todayStr = sdf.format(new Date());

            PDExtendedGraphicsState extGState = new PDExtendedGraphicsState();
            extGState.setNonStrokingAlphaConstant(options.getOpacity());
            extGState.setStrokingAlphaConstant(options.getOpacity());

            int stampedCount = 0;

            for (int i = 0; i < totalPages; i++) {
                int pageNum = i + 1;
                if (!stampSet.contains(pageNum)) continue;

                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float pageW = box.getWidth();
                float pageH = box.getHeight();

                // Resolve macros in stamp text: {date}, {sheet}, {total}
                String resolvedText = options.getText()
                        .replace("{date}", todayStr)
                        .replace("{sheet}", "Sheet " + pageNum + " of " + totalPages)
                        .replace("{total}", String.valueOf(totalPages));

                float textWidth = font.getStringWidth(resolvedText) / 1000f * options.getFontSize();
                float textHeight = font.getFontDescriptor().getCapHeight() / 1000f * options.getFontSize();

                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.saveGraphicsState();
                    cs.setGraphicsStateParameters(extGState);
                    cs.setNonStrokingColor(options.getColor());
                    cs.setStrokingColor(options.getColor());
                    cs.setLineWidth(2.0f);

                    float x = 0;
                    float y = 0;
                    double angleRad = 0;

                    switch (options.getPosition()) {
                        case TOP_LEFT:
                            x = box.getLowerLeftX() + 40;
                            y = box.getUpperRightY() - 40 - textHeight;
                            break;
                        case TOP_RIGHT:
                            x = box.getUpperRightX() - 40 - textWidth;
                            y = box.getUpperRightY() - 40 - textHeight;
                            break;
                        case BOTTOM_LEFT:
                            x = box.getLowerLeftX() + 40;
                            y = box.getLowerLeftY() + 40;
                            break;
                        case BOTTOM_RIGHT:
                            x = box.getUpperRightX() - 40 - textWidth;
                            y = box.getLowerLeftY() + 40;
                            break;
                        case CENTER:
                            x = box.getLowerLeftX() + (pageW - textWidth) / 2f;
                            y = box.getLowerLeftY() + (pageH - textHeight) / 2f;
                            break;
                        case DIAGONAL:
                        default:
                            x = box.getLowerLeftX() + (pageW - textWidth) / 2f;
                            y = box.getLowerLeftY() + (pageH - textHeight) / 2f;
                            angleRad = Math.atan2(pageH, pageW);
                            break;
                    }

                    if (options.getPosition() == StampPosition.DIAGONAL) {
                        float cx = box.getLowerLeftX() + pageW / 2f;
                        float cy = box.getLowerLeftY() + pageH / 2f;
                        Matrix m = new Matrix();
                        m.translate(cx, cy);
                        m.rotate(angleRad);
                        cs.transform(m);

                        // Draw centered at origin
                        float localX = -textWidth / 2f;
                        float localY = -textHeight / 2f;

                        if (options.isDrawBorder()) {
                            float pad = options.getFontSize() * 0.35f;
                            cs.addRect(localX - pad, localY - pad * 0.7f, textWidth + pad * 2f, textHeight + pad * 1.6f);
                            cs.stroke();
                        }

                        cs.beginText();
                        cs.setFont(font, options.getFontSize());
                        cs.newLineAtOffset(localX, localY);
                        cs.showText(resolvedText);
                        cs.endText();

                    } else {
                        // Standard orthogonal placement
                        if (options.isDrawBorder()) {
                            float pad = options.getFontSize() * 0.35f;
                            cs.addRect(x - pad, y - pad * 0.7f, textWidth + pad * 2f, textHeight + pad * 1.6f);
                            cs.stroke();
                        }

                        cs.beginText();
                        cs.setFont(font, options.getFontSize());
                        cs.newLineAtOffset(x, y);
                        cs.showText(resolvedText);
                        cs.endText();
                    }

                    cs.restoreGraphicsState();
                }

                stampedCount++;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            byte[] outBytes = baos.toByteArray();
            String outName = baseName + "_stamped.pdf";

            return new StampResult(outBytes, outName, totalPages, stampedCount, options.getText());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to apply stamp to PDF: " + e.getMessage(), e);
        }
    }

    private static boolean isValidPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        return header.contains("%PDF-");
    }

    private static String sanitizeBaseName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "document";
        String clean = filename.trim().replaceAll("(?i)\\.pdf$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isEmpty() ? "document" : clean;
    }
}

