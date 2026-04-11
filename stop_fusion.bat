@echo off
chcp 65001 >nul
title Project Fusion - 停止所有服务

echo 正在停止 Project Fusion...

REM 停止 Scrcpy
taskkill /IM scrcpy.exe /F >nul 2>&1

REM 停止 Python Bridge 进程
wmic process where "CommandLine like '%%bridge%%'" call terminate >nul 2>&1

REM 停止 Input Leap
taskkill /IM input-leapd.exe /F >nul 2>&1
taskkill /IM input-leaps.exe /F >nul 2>&1

REM 停止 KDE Connect (可选 - 通常保持运行)
REM taskkill /IM kdeconnect-cli.exe /F >nul 2>&1

echo.
echo Project Fusion 已停止
timeout /t 2 >nul
