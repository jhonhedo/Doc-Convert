#!/usr/bin/env bash
set -e

echo "================================================================="
echo "  Starting CADConvert DXF to PDF Web Server..."
echo "================================================================="

if [ -f "target/dxf-to-pdf-1.0.0-SNAPSHOT.jar" ]; then
    echo "Launching packaged JAR..."
    java -Djava.awt.headless=true -jar target/dxf-to-pdf-1.0.0-SNAPSHOT.jar --port "${PORT:-8080}"
else
    echo "Building and launching via Maven..."
    mvn clean package -DskipTests
    java -Djava.awt.headless=true -jar target/dxf-to-pdf-1.0.0-SNAPSHOT.jar --port "${PORT:-8080}"
fi
