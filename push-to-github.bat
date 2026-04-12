@echo off
echo ========================================
echo Project Fusion - Git 推送脚本
echo ========================================
echo.

cd /d %~dp0

echo 检查 Git 状态...
git status

echo.
echo 推送到 GitHub...
git push

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo ✓ 推送成功！
    echo ========================================
) else (
    echo.
    echo ========================================
    echo ✗ 推送失败，请检查网络连接
    echo ========================================
)

echo.
pause
