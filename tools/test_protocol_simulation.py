#!/usr/bin/env python3
"""Run the v2 security and transport regressions. No real device is simulated as verified."""
import sys
import unittest
from pathlib import Path
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "desktop"))
suite = unittest.defaultTestLoader.discover(str(root / "desktop" / "tests"))
sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
