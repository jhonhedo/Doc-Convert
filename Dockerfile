# Build stage using Maven and Temurin OpenJDK 21
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build runnable fat JAR
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install font packages for CAD text rendering if needed
RUN apk add --no-cache fontconfig ttf-dejavu

# Copy built JAR from build stage
COPY --from=build /app/target/dxf-to-pdf-1.0.0-SNAPSHOT.jar app.jar

# Expose HTTP port
EXPOSE 8080

# Environment configuration
ENV PORT=8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

# Launch DxfServer
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --port $PORT"]
