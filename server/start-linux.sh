#!/usr/bin/env bash
# Creates a local virtualenv on first run, then starts the AndroidDeck server.
set -euo pipefail
cd "$(dirname "$0")"
PY="${PYTHON:-python3}"
if [ ! -x .venv/bin/python ]; then
    "$PY" -m venv .venv
    .venv/bin/python -m pip install -q --upgrade pip
fi
if [ ! -f .venv/.installed ] || [ requirements.txt -nt .venv/.installed ]; then
    .venv/bin/python -m pip install -q -r requirements.txt
    touch .venv/.installed
fi
exec .venv/bin/python -m androiddeck "$@"
