@echo off
rem Creates a local virtualenv on first run, then starts the AndroidDeck server.
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    py -3 -m venv .venv 2>nul || python -m venv .venv
    if not exist .venv\Scripts\python.exe (
        echo Python 3.10+ is required: https://www.python.org/downloads/
        pause
        exit /b 1
    )
    .venv\Scripts\python.exe -m pip install -q --upgrade pip
)
if not exist .venv\.installed (
    .venv\Scripts\python.exe -m pip install -q -r requirements.txt || (pause & exit /b 1)
    type nul > .venv\.installed
)
.venv\Scripts\python.exe -m androiddeck %*
pause
