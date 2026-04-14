@echo off
REM 推送 sherpa-onnx 模型到手机
REM 使用方法: push-models-to-phone.bat [设备ID]

setlocal enabledelayedexpansion

set "PROJECT_ROOT=%~dp0"
set "ADB=C:\Users\wang\Desktop\scrcpy-win64-v3.3.4\adb.exe"
set "PACKAGE=com.fusion.companion"

echo ========================================
echo   推送 sherpa-onnx 模型到手机
echo ========================================

REM 检查 ADB
"%ADB%" devices
if errorlevel 1 (
    echo [ERROR] ADB 不可用
    exit /b 1
)

REM 获取设备
if "%~1"=="" (
    for /f "tokens=1" %%d in ('"%ADB%" devices ^| findstr "device$"') do set "DEVICE=%%d"
) else (
    set "DEVICE=%~1"
)

if "%DEVICE%"=="" (
    echo [ERROR] 未找到设备
    exit /b 1
)
echo 设备: %DEVICE%

REM 创建目标目录
echo.
echo [1/3] 创建目标目录...
"%ADB%" -s %DEVICE% shell "mkdir -p /sdcard/Android/data/%PACKAGE%/files/models"
"%ADB%" -s %DEVICE% shell "mkdir -p /data/local/tmp/sherpa-models"

REM 推送 .so 文件 (arm64-v8a)
echo.
echo [2/3] 推送 .so 文件...
set "JNI_DIR=%PROJECT_ROOT%android-companion\app\src\main\jniLibs\arm64-v8a"
if exist "%JNI_DIR%\*.so" (
    "%ADB%" -s %DEVICE% push "%JNI_DIR%\*.so" /data/local/tmp/sherpa-models/
    echo .so 文件已推送到 /data/local/tmp/sherpa-models/
) else (
    echo [WARN] 未找到 .so 文件: %JNI_DIR%
)

REM 推送模型文件
echo.
echo [3/3] 推送模型文件...
set "MODELS_DIR=%PROJECT_ROOT%android-companion\app\src\main\assets\models"
if exist "%MODELS_DIR%\*.onnx" (
    "%ADB%" -s %DEVICE% push "%MODELS_DIR%\" /sdcard/Android/data/%PACKAGE%/files/models/
    echo 模型文件已推送到 /sdcard/Android/data/%PACKAGE%/files/models/
) else (
    echo [WARN] 未找到模型文件: %MODELS_DIR%
)

echo.
echo ========================================
echo 推送完成！
echo.
echo 目标路径:
echo   .so  : /data/local/tmp/sherpa-models/ (NativeLoader 会自动搜索)
echo   模型 : /sdcard/Android/data/%PACKAGE%/files/models/
echo ========================================

"%ADB%" -s %DEVICE% shell "ls -lh /sdcard/Android/data/%PACKAGE%/files/models/"
"%ADB%" -s %DEVICE% shell "ls -lh /data/local/tmp/sherpa-models/"
