@echo off
title xiaoxi

REM Switch to backend directory
cd /d "%~dp0backend" 2>nul
if errorlevel 1 (
    echo [ERR] Cannot enter backend directory
    pause
    exit /b 1
)

echo.
echo    ========================================
echo      xiaoxi v0.4.1
echo    ========================================
echo.

REM --- Check Python venv ---
if not exist ".venv\Scripts\python.exe" (
    echo    [...] Creating virtual environment...
    python -m venv .venv
    if errorlevel 1 (
        echo    [ERR] Failed to create .venv - Python 3.10+ required
        pause
        exit /b 1
    )
)

REM --- Install missing dependencies ---
echo    [...] Checking dependencies...
.venv\Scripts\pip.exe install -r requirements.txt -q 2>&1
if errorlevel 1 (
    echo    [ERR] pip install failed - check your network
    pause
    exit /b 1
)
echo    [OK] Dependencies ready

REM --- Check .env ---
if not exist "config\.env" (
    echo    [ERR] config\.env not found
    echo    Copy config\.env.example to config\.env and fill in API keys
    pause
    exit /b 1
)

REM --- Free port 8000 ---
for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":8000 "') do (
    echo    [WARN] Port 8000 in use by PID %%a
    echo    [WARN] Please close the other process, or press any key to force kill
    pause
    taskkill /PID %%a /F >nul 2>&1
    timeout /t 2 /nobreak >nul
)

echo    [OK] All ready
echo.
echo      Page:   http://127.0.0.1:8000
echo      Health: http://127.0.0.1:8000/api/health
echo.
echo    Press Ctrl+C to stop
echo    ========================================
echo.

REM --- Start ---
set PYTHONIOENCODING=utf-8
.venv\Scripts\python.exe main.py

REM --- Server stopped ---
echo.
echo    ========================================
echo    xiaoxi stopped (exit code: %errorlevel%)
echo    ========================================
echo.
pause
