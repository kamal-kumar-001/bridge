#!/usr/bin/env python3
"""Compatibility launcher for the secure Bridge desktop companion (protocol v2)."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "desktop"))
from bridge.app import main
if __name__ == "__main__":
    main()
