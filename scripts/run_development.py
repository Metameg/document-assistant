#!/usr/bin/env python3
"""Load local secrets without putting them in process arguments; start the development app."""
import hashlib
import shutil
import os
import sys
sys.dont_write_bytecode = True
from pathlib import Path
from evaluate_rag import load_dotenv

root = Path(__file__).resolve().parent.parent
os.chdir(root)
load_dotenv(root / ".env")
built = root / "target/document-assistant-0.0.1-SNAPSHOT.jar"
# A running Spring Boot jar must not be replaced by a subsequent Maven package.
# Its classloader reads dependencies lazily; a stable snapshot permits local rebuilds.
runtime = root / "target/runtime"
runtime.mkdir(exist_ok=True)
snapshot = runtime / ("app-" + hashlib.sha256(built.read_bytes()).hexdigest()[:16] + ".jar")
if not snapshot.exists():
    shutil.copyfile(built, snapshot)
os.execvp("java", ["java", "-jar", str(snapshot),
                   "--spring.config.additional-location=file:config/application-local.properties,file:config/application-development.properties"])
