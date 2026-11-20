@echo off
rem Windows build: double-click or run "build.bat"  -> build\Lumina.exe + releases\Lumina.exe
rem Uses the C# compiler that ships with Windows (.NET Framework 4.x), nothing to install.
setlocal
cd /d "%~dp0"
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist build mkdir build
if not exist releases mkdir releases
"%CSC%" -nologo -target:winexe -platform:x64 -unsafe -optimize+ -codepage:65001 ^
  -win32manifest:res\app.manifest -win32icon:res\lumina.ico ^
  -r:System.Windows.Forms.dll -r:System.Drawing.dll ^
  -out:build\Lumina.exe src\*.cs
if errorlevel 1 (echo Build failed. & pause & exit /b 1)
copy /y build\Lumina.exe releases\Lumina.exe >nul 2>&1
if errorlevel 1 (echo Built build\Lumina.exe, but releases\Lumina.exe is in use: quit Lumina from the tray and re-run. & pause & exit /b 1)
echo Built releases\Lumina.exe
