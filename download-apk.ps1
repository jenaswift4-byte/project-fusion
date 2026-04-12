# Project Fusion APK 下载脚本
# 从 GitHub Actions 下载最新编译的 APK

$ErrorActionPreference = "Stop"
$repo = "jenaswift4-byte/project-fusion"
$downloadDir = "$PSScriptRoot\apk-downloads"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Project Fusion - APK 下载工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 创建下载目录
if (-not (Test-Path $downloadDir)) {
    New-Item -ItemType Directory -Path $downloadDir | Out-Null
    Write-Host "✓ 创建下载目录：$downloadDir" -ForegroundColor Green
}

# 获取 GitHub Token（如果已登录）
$githubToken = $env:GITHUB_TOKEN

$headers = @{
    "Accept" = "application/vnd.github.v3+json"
    "User-Agent" = "PowerShell-APK-Downloader"
}

if ($githubToken) {
    $headers["Authorization"] = "token $githubToken"
    Write-Host "✓ 使用 GitHub Token 认证" -ForegroundColor Green
}

Write-Host ""
Write-Host "正在获取最新的 Actions 运行记录..." -ForegroundColor Yellow

try {
    # 获取最近的 workflow runs
    $response = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs?branch=main&per_page=5" -Headers $headers
    $runs = $response.workflow_runs
    
    if ($runs.Count -eq 0) {
        Write-Host "✗ 未找到任何 Actions 运行记录" -ForegroundColor Red
        exit 1
    }
    
    # 显示运行记录
    Write-Host ""
    Write-Host "找到 $($runs.Count) 个最近的编译任务:" -ForegroundColor Cyan
    Write-Host ""
    
    for ($i = 0; $i -lt $runs.Count; $i++) {
        $run = $runs[$i]
        $status = if ($run.conclusion -eq "success") { "✓ 成功" } elseif ($run.status -eq "in_progress") { "⏳ 进行中" } else { "✗ 失败" }
        $color = if ($run.conclusion -eq "success") { "Green" } elseif ($run.status -eq "in_progress") { "Yellow" } else { "Red" }
        Write-Host "  [$i] $($run.created_at) - $status - $($run.head_branch)" -ForegroundColor $color
    }
    
    Write-Host ""
    Write-Host "请选择要下载的编译任务 (0-$($runs.Count-1)): " -ForegroundColor Yellow -NoNewline
    $selectedIndex = Read-Host
    
    if ($selectedIndex -lt 0 -or $selectedIndex -ge $runs.Count) {
        Write-Host "✗ 无效的选择" -ForegroundColor Red
        exit 1
    }
    
    $selectedRun = $runs[$selectedIndex]
    Write-Host ""
    Write-Host "已选择：$($selectedRun.created_at) - $($selectedRun.head_branch)" -ForegroundColor Cyan
    Write-Host ""
    
    # 检查是否成功
    if ($selectedRun.conclusion -ne "success") {
        Write-Host "⚠ 该编译任务未成功完成，是否继续？(y/n): " -ForegroundColor Yellow -NoNewline
        $confirm = Read-Host
        if ($confirm -ne "y") {
            exit 0
        }
    }
    
    # 获取 artifacts
    Write-Host "正在获取编译产物..." -ForegroundColor Yellow
    $artifactsResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$($selectedRun.id)/artifacts" -Headers $headers
    $artifacts = $artifactsResponse.artifacts
    
    if ($artifacts.Count -eq 0) {
        Write-Host "✗ 未找到任何编译产物" -ForegroundColor Red
        exit 1
    }
    
    Write-Host ""
    Write-Host "找到以下编译产物:" -ForegroundColor Cyan
    Write-Host ""
    
    for ($i = 0; $i -lt $artifacts.Count; $i++) {
        $artifact = $artifacts[$i]
        Write-Host "  [$i] $($artifact.name) ($([math]::Round($artifact.size_in_bytes/1MB, 2)) MB) - 过期时间：$($artifact.expired_at)" -ForegroundColor Green
    }
    
    Write-Host ""
    Write-Host "请选择要下载的产物 (输入数字，或输入 'all' 下载全部): " -ForegroundColor Yellow -NoNewline
    $selectedArtifact = Read-Host
    
    $toDownload = @()
    if ($selectedArtifact -eq "all") {
        $toDownload = $artifacts
    } else {
        $idx = [int]$selectedArtifact
        if ($idx -lt 0 -or $idx -ge $artifacts.Count) {
            Write-Host "✗ 无效的选择" -ForegroundColor Red
            exit 1
        }
        $toDownload = @($artifacts[$idx])
    }
    
    # 下载文件
    foreach ($artifact in $toDownload) {
        Write-Host ""
        Write-Host "正在下载：$($artifact.name)..." -ForegroundColor Yellow
        
        $downloadUrl = $artifact.archive_download_url
        $zipPath = "$downloadDir\$($artifact.name).zip"
        
        # 下载 ZIP 文件
        Invoke-WebRequest -Uri $downloadUrl -Headers $headers -OutFile $zipPath
        
        Write-Host "✓ 下载完成：$zipPath" -ForegroundColor Green
        
        # 解压
        Write-Host "正在解压..." -ForegroundColor Yellow
        Expand-Archive -Path $zipPath -DestinationPath "$downloadDir\$($artifact.name)" -Force
        Remove-Item $zipPath
        
        Write-Host "✓ 解压完成" -ForegroundColor Green
        
        # 显示 APK 文件路径
        $apkPath = Get-ChildItem -Path "$downloadDir\$($artifact.name)" -Filter "*.apk" | Select-Object -First 1
        if ($apkPath) {
            Write-Host ""
            Write-Host "APK 文件位置：" -ForegroundColor Cyan
            Write-Host "  $($apkPath.FullName)" -ForegroundColor Green
            Write-Host ""
            Write-Host "安装到手机：" -ForegroundColor Cyan
            Write-Host "  adb install `"$($apkPath.FullName)`"" -ForegroundColor White
        }
    }
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "下载完成！" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "下载目录：$downloadDir" -ForegroundColor White
    
} catch {
    Write-Host ""
    Write-Host "✗ 发生错误：" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ""
    Write-Host "可能的原因:" -ForegroundColor Yellow
    Write-Host "  1. 网络连接问题" -ForegroundColor White
    Write-Host "  2. GitHub API 限制（请设置 GITHUB_TOKEN）" -ForegroundColor White
    Write-Host "  3. 没有编译产物（请等待 Actions 完成）" -ForegroundColor White
    Write-Host ""
    Write-Host "提示：" -ForegroundColor Cyan
    Write-Host "  可以在 GitHub 设置中创建 Personal Access Token" -ForegroundColor White
    Write-Host "  然后设置环境变量：`$env:GITHUB_TOKEN=`"your_token`"" -ForegroundColor White
}
