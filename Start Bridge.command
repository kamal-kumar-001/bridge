#!/bin/bash
# Double-click on macOS, or run with bash from Terminal. No sudo or system installs.
set -euo pipefail
cd "$(dirname "$0")"
fail() { printf '\n%s\n' "$1"; read -r -p 'Press Return to close... ' _; exit 1; }
command -v python3 >/dev/null || fail 'Install Python 3.10+ with Tk from python.org, then reopen Bridge.'
python3 -c 'import sys; assert sys.version_info >= (3,10); import tkinter' 2>/dev/null || fail 'Bridge needs Python 3.10+ with Tk. Install the macOS Python package from python.org, then reopen Bridge.'
if [ ! -x .venv/bin/python ]; then
    printf '%s\n' 'First run creates a private Python environment in this folder and installs Bridge dependencies from PyPI.'
    read -r -p 'Continue? [y/N] ' answer
    case "$answer" in y|Y|yes|YES) ;; *) exit 0 ;; esac
    python3 -m venv .venv || fail 'Could not create the local Python environment.'
    .venv/bin/python -m pip install -r desktop/requirements.txt || fail 'Dependency install failed. Check your internet connection and try again.'
fi
printf '%s\n' 'Opening Bridge. Keep this Terminal window open while testing.'
.venv/bin/python tools/desktop_bridge_agent.py || fail 'Bridge stopped with an error. Send a screenshot of this window.'
