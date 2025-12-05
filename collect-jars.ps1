# 收集并重命名components/apimgt下的所有jar文件
$sourceDir = "components\apimgt"
$targetDir = "components\apimgt\target-new"

# 确保目标目录存在
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
}

# 查找所有jar文件
$jarFiles = Get-ChildItem -Path $sourceDir -Recurse -Filter "*.jar" -File | Where-Object {
    # 排除target-new目录本身
    $_.FullName -notlike "*\target-new\*"
}

Write-Host "find $($jarFiles.Count) jar files"
Write-Host ""

$copiedCount = 0
foreach ($jar in $jarFiles) {
    # 获取原始文件名
    $originalName = $jar.Name
    
    # 将 - 替换为 _
    $newName = $originalName -replace '-', '_'
    
    # 目标文件路径
    $targetPath = Join-Path $targetDir $newName
    
    # 复制并重命名
    try {
        Copy-Item -Path $jar.FullName -Destination $targetPath -Force
        Write-Host "✓ $originalName -> $newName"
        $copiedCount++
    } catch {
        Write-Host "✗ copy: $originalName - $_" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "finish! copy success $copiedCount files $targetDir" -ForegroundColor Green

