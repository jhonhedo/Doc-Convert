# Doc-Convert

High-performance, clean-room DXF to Vector PDF converter and web application written in Java.

## Features

- **Accurate DXF Vector Rendering**: Supports lines, polylines, lwpolylines, circles, arcs, ellipses, splines, text, dimensions, and solid/hatch fills.
- **Pure Vector Output**: Generates crisp, scalable vector PDF documents using Apache PDFBox.
- **Built-in Web UI & REST API**: Includes a lightweight HTTP server with a modern browser interface and REST endpoints.
- **Docker & Cloud-Ready**: Fully containerized with fontconfig support, ready for deployment to Google Cloud Run, AWS, or local Docker.
- **Fast & Lightweight**: Sub-second conversion times with minimal memory footprint.

## Quick Start

### Run with Maven
```bash
mvn clean package
mvn exec:java
```

### Run with Docker
```bash
docker build -t doc-convert .
docker run -p 8080:8080 doc-convert
```

Access the application in your browser at `http://localhost:8080`.

## API Endpoints

- `POST /api/convert`: Upload DXF file (multipart or body) to receive vector PDF.
- `POST /api/preview`: Upload DXF file to receive rendered PNG preview thumbnail.
- `GET /api/health`: Service health check endpoint.
- `GET /api/sample`: Download a sample CAD DXF file.
