@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  Git auto-sync script  (pull --rebase -> add -> commit -> push)
REM  Usage: double-click this .bat, or run "sync.bat" in cmd.
REM  Put it in your repo root so %~dp0 points to the repo.
REM ============================================================

REM  Cd to the directory where this script lives (repo root)
cd /d "%~dp0"

REM  Build YYYYMMDD timestamp for the commit message
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd"') do set "YMD=%%i"

echo [1/4] git pull --rebase (autostash local changes)
git pull --rebase --autostash
if %errorlevel% neq 0 (
    echo [ERROR] pull --rebase failed, possibly a merge conflict.
    echo         Resolve the conflict manually, then run sync.bat again.
    pause
    exit /b 1
)

echo [2/4] git add -A
git add -A

echo [3/4] check for staged changes
git diff --cached --quiet
if %errorlevel%==0 (
    echo [INFO] Nothing to commit, working tree clean.
) else (
    echo [3/4] git commit -m "update_%YMD%"
    git commit -m "update_%YMD%"
)

echo [4/4] git push
git push

echo [DONE] sync finished.
pause
