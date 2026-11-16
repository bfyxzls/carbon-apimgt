# Build carbon-apimgt with JDK 21 (required for v9_33_122+)
# Usage: .\build-jdk21.ps1 clean install -DskipTests
# After a successful package/install/deploy/verify, jars are copied to dist-jars/
# as {artifactId}_{version}.jar

$Jdk21Home = "C:\Users\User\.jdks\graalvm-jdk-21.0.6"
$RepoRoot = $PSScriptRoot
$DistDir = Join-Path $RepoRoot "dist-jars"

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
$mvnExit = $LASTEXITCODE
if ($mvnExit -ne 0) {
    exit $mvnExit
}

$goalLine = ($args -join " ")
if ($goalLine -notmatch '\b(package|install|deploy|verify)\b') {
    exit 0
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

# Search from current working directory (supports building a single module via cd)
$searchRoot = (Get-Location).Path
$jars = Get-ChildItem -Path $searchRoot -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
    Where-Object {
        $_.DirectoryName -match '[\\/]target$' -and
        $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' -and
        $_.Name -notmatch '^original-'
    }

$count = 0
foreach ($jar in $jars) {
    # Rewrite only the hyphen before the version (version starts with a digit)
    if ($jar.Name -notmatch '^(?<id>.+)-(?<ver>\d[\w\.\-]*)\.jar$') {
        continue
    }
    $newName = "{0}_{1}.jar" -f $Matches['id'], $Matches['ver']
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $DistDir $newName) -Force
    $count++
}

Write-Host ""
Write-Host "Collected $count jar(s) into $DistDir (artifactId_version.jar)"
exit 0
