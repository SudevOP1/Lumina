#!/bin/bash
# Windows (Git Bash) build. Usage: ./build.sh  -> build/Lumina.exe + releases/Lumina.exe
# Uses the C# compiler that ships with Windows (.NET Framework 4.x), nothing to install.
set -e
cd "$(dirname "$0")"
CSC=${CSC:-/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe}
export MSYS_NO_PATHCONV=1 # csc misreads forward-slash paths; pass Windows-style ones

mkdir -p build releases
"$CSC" -nologo -target:winexe -platform:x64 -unsafe -optimize+ -codepage:65001 \
  '-win32manifest:res\app.manifest' '-win32icon:res\lumina.ico' \
  -r:System.Windows.Forms.dll -r:System.Drawing.dll \
  '-out:build\Lumina.exe' 'src\*.cs'
if ! cp build/Lumina.exe releases/Lumina.exe 2>/dev/null; then
  echo "Built build/Lumina.exe, but releases/Lumina.exe is in use: quit Lumina from the tray and re-run."
  exit 1
fi
ls -la releases/Lumina.exe
