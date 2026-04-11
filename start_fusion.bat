@echo off
chcp 65001 >nul
title Project Fusion - 跨设备无缝融合

echo.
echo ========================================
echo     Project Fusion - 跨设备无缝融合
echo     Android + Windows Seamless Bridge
echo ========================================
echo.

REM 切到项目目录
cd /d "%~dp0"

REM 检查 Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未安装 Python，请先安装 Python 3.10+
    echo 下载: https://www.python.org/downloads/
    pause
    exit /b 1
)

REM 安装依赖
echo [1/3] 检查 Python 依赖...
pip show pyyaml >nul 2>&1
if errorlevel 1 (
    echo       安装 pyyaml...
    pip install pyyaml -q
)
pip show pyperclip >nul 2>&1
if errorlevel 1 (
    echo       安装 pyperclip...
    pip install pyperclip -q
)
pip show pystray >nul 2>&1
if errorlevel 1 (
    echo       安装 pystray...
    pip install pystray -q
)
pip show Pillow >nul 2>&1
if errorlevel 1 (
    echo       安装 Pillow...
    pip install Pillow -q
)

REM 检查 ADB 设备
echo [2/3] 检查 Android 设备...
set ADB_PATH=C:\Users\wang\Desktop\scrcpy-win64-v3.3.4\adb.exe
%ADB_PATH% devices -l 2>nul | findstr "device" >nul
if errorlevel 1 (
    echo [警告] 未检测到 Android 设备
    echo   请确保:
    echo   1. 手机已通过 USB 连接
    echo   2. USB 调试已开启
    echo.
    echo 按任意键继续，或 Ctrl+C 退出...
    pause >nul
)

REM 启动 Bridge Daemon
echo [3/3] 启动 Project Fusion...
echo.

python -m bridge %*

pause
