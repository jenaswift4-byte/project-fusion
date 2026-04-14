@echo off
set ADB=C:\Users\wang\Desktop\scrcpy-win64-v3.3.4\adb.exe
echo 获取应用 PID...
for /f "delims=" %%i in ('%ADB% shell pidof com.fusion.companion') do set PID=%%i
echo 应用 PID: %PID%
echo.
echo ASR 相关日志:
%ADB% logcat -d --pid=%PID% | findstr /C:"StreamingASR"
echo.
echo 错误日志:
%ADB% logcat -d --pid=%PID% *:E | findstr /C:"sherpa"
pause
