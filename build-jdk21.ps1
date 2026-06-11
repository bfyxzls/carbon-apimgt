# Build carbon-apimgt with JDK 21 (required for v9_33_122+)
# Usage: .\build-jdk21.ps1 clean install -DskipTests

$Jdk21Home = "C:\Users\User\.jdks\graalvm-jdk-21.0.6"

if (-not (Test-Path "$Jdk21Home\bin\java.exe")) {
    Write-Error "JDK 21 not found at: $Jdk21Home"
    exit 1
}

$env:JAVA_HOME = $Jdk21Home
$env:PATH = "$Jdk21Home\bin;$env:PATH"

Write-Host "JAVA_HOME = $env:JAVA_HOME"
java -version
Write-Host ""
mvn @args
