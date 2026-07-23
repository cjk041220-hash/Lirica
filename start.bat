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
echo      xiaoxi v0.4.4
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

REM --- Build frontend bundle (FastAPI serves web/dist at :8000) ---
call :build_fe

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
echo      App:    http://127.0.0.1:8000   (frontend + API, single port)
echo      Health: http://127.0.0.1:8000/api/health
echo      Dev UI: npm run dev in web/      (:3000, hot reload)
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
goto :eof

REM ============================================================
REM  Frontend build subroutine
REM  生产模式下 FastAPI 直接把 web/dist 挂到 :8000 根路径，
REM  所以这里在缺 dist 时尝试构建一次（缺 npm 则优雅跳过）。
REM ============================================================
:build_fe
if exist "..\web\dist\index.html" (
    echo    [OK] Frontend bundle present (web/dist)
    exit /b 0
)
where npm >nul 2>nul
if errorlevel 1 (
    echo    [WARN] npm not found - skip frontend build
    echo           Install Node.js for the integrated UI at :8000,
    echo           or run 'npm run dev' inside web/ for the dev UI on :3000.
    exit /b 0
)
echo    [...] Building frontend (web/dist) ...
pushd "..\web" >nul 2>&1
if not exist "node_modules\" (
    echo    [...] Installing frontend dependencies ...
    call npm install -s
    if errorlevel 1 (
        echo    [WARN] npm install failed - skip frontend build
        popd >nul 2>&1
        exit /b 0
    )
)
call npm run build
if errorlevel 1 (
    echo    [WARN] Frontend build failed - :8000 will serve API only
    popd >nul 2>&1
    exit /b 0
)
popd >nul 2>&1
echo    [OK] Frontend built - integrated UI available at :8000
exit /b 0
