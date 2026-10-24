# Build stage using Maven and Temurin OpenJDK 21
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build runnable fat JAR
RUN mvn clean package -DskipTests

# Runtime stage: Use Debian-based Temurin runtime for official LibreDWG and font packages
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install LibreDWG CLI (dwg2dxf) and system fonts for vector rendering
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      libredwg-tools \
      fontconfig \
      fonts-dejavu-core \
      ca-certificates \
      curl && \
    rm -rf /var/lib/apt/lists/*

# Copy built JAR from build stage
COPY --from=build /app/target/dxf-to-pdf-1.0.0-SNAPSHOT.jar app.jar

# Expose HTTP port
EXPOSE 8080

# Environment configuration
ENV PORT=8080
ENV DWG2DXF_BIN=dwg2dxf
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

# Launch Server
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --port $PORT"]
