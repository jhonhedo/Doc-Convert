@echo off
setlocal
echo =================================================================
echo   Starting CADConvert DXF to PDF Web Server...
echo =================================================================

REM Check if target jar exists, otherwise build it or run via mvn
if exist "target\dxf-to-pdf-1.0.0-SNAPSHOT.jar" (
    echo Launching packaged JAR...
    java -Djava.awt.headless=true -jar target\dxf-to-pdf-1.0.0-SNAPSHOT.jar --port 8080
) else (
    echo Building and launching via Maven...
    call mvn clean package -DskipTests
    if %ERRORLEVEL% EQU 0 (
        java -Djava.awt.headless=true -jar target\dxf-to-pdf-1.0.0-SNAPSHOT.jar --port 8080
    ) else (
        echo Build failed. Running via exec:java...
        call mvn exec:java -Dexec.mainClass="com.converter.dxf.server.DxfServer"
    )
)
pause
