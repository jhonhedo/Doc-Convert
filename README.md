# CADConvert / DocConvert

High-performance, clean-room CAD vector rendering and PDF optimization utility suite written in Java.

## Multi-Tool Suite Overview

1. **DXF to Vector PDF Converter** (`/dxf-to-pdf`): Full support for lines, polylines, bulges, circles, arcs, ellipses, NURBS splines, text, dimensions, and architectural hatches.
2. **DWG to Vector PDF Converter** (`/dwg-to-pdf`): Headless bridge decoding native AutoCAD DWG binary formats (`.dwg`) into clean-room vector PDF streams via LibreDWG.
3. **PDF Merging Utility** (`/merge-pdf`): Lossless stream concatenation of multiple PDF documents in volatile RAM, preserving vector paths, embedded fonts, and page rotations.
4. **PDF Compression Utility** (`/compress-pdf`): 3-tier selective image downsampling (Mild, Recommended, Extreme) and stream re-compression with real-time reduction statistics.
5. **Tools Hub Directory** (`/tools`): Dedicated portal displaying the complete utility suite.

## Architecture & Security Highlights

- **100% In-Memory Processing**: Zero disk retention. Drawings and documents are processed in volatile RAM and immediately discarded.
- **Pure Vector Output**: Crisp, scalable PDF output via Apache PDFBox with infinite zoom precision.
- **Concurrency & Upload Bounds**: Configurable concurrency semaphores and max upload limits to prevent resource exhaustion.
- **Docker & Cloud Run Ready**: Containerized runtime with pre-configured `libredwg-tools`, `fontconfig`, and TrueType DejaVu fonts.

## Quick Start

### Run with Maven (Local Development)
```bash
mvn clean compile exec:java
```

### Run with Docker (Full Suite with LibreDWG)
```bash
docker build -t cadconvert:latest .
docker run -p 8080:8080 cadconvert:latest
```

Access the application in your browser at `http://localhost:8080`.

## REST API Endpoints

- `POST /api/convert`: Upload DXF or DWG file to receive vector PDF (supports raw binary or multipart).
- `POST /api/convert/dwg`: Explicit endpoint for AutoCAD DWG binary uploads.
- `POST /api/preview`: Generate instant PNG preview thumbnail of Page 1.
- `POST /api/pdf/merge`: Upload 2+ PDF files to merge into a single PDF master document.
- `POST /api/pdf/compress`: Compress and downsample PDF (query parameter: `level=low|medium|high`).
- `GET /api/health`: Service health check with uptime, memory stats, and DWG bridge availability.
- `GET /api/sample`: Download bundled architectural sample CAD drawing.

## Clean SEO Routes

- `/` : CADConvert Studio & Tools Hub
- `/dxf-to-pdf` : Dedicated DXF to Vector PDF Converter
- `/dwg-to-pdf` : Dedicated DWG to Vector PDF Converter
- `/merge-pdf` : Dedicated PDF Merger Studio
- `/compress-pdf` : Dedicated PDF Compressor Studio
- `/tools` : All Tools Hub & Directory
