# Stage 1: Build Java application using Maven and Temurin OpenJDK 21
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build runnable fat JAR
RUN mvn clean package -DskipTests

# Stage 2: Build LibreDWG (dwg2dxf) from official GNU source
FROM ubuntu:22.04 AS dwg-builder
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      build-essential \
      ca-certificates \
      curl \
      pkg-config && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/libredwg
RUN curl -fsSL https://ftp.gnu.org/gnu/libredwg/libredwg-0.12.5.tar.gz | tar -xz --strip-components=1 && \
    ./configure --prefix=/usr/local --disable-shared --enable-static && \
    make -j$(nproc) && \
    make install

# Stage 3: Runtime stage using Temurin JRE
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install system fonts and utilities for vector rendering
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      fontconfig \
      fonts-dejavu-core \
      ca-certificates \
      curl && \
    rm -rf /var/lib/apt/lists/*

# Copy compiled dwg2dxf binary
COPY --from=dwg-builder /usr/local/bin/dwg2dxf /usr/local/bin/dwg2dxf

# Copy built JAR from build stage
COPY --from=build /app/target/dxf-to-pdf-1.0.0-SNAPSHOT.jar app.jar

# Expose HTTP port
EXPOSE 8080

# Environment configuration
ENV PORT=8080
ENV DWG2DXF_BIN=/usr/local/bin/dwg2dxf
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

# Launch Server
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --port $PORT"]

