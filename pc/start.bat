@echo off
setlocal EnableExtensions
title ShengQiao - setup
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  ShengQiao launcher.
rem  First run downloads the official portable Python 3.12 (about 11 MB) into
rem  .\runtime and checks its Python Software Foundation code signature.
rem  Nothing is installed into Windows; delete the folder to remove everything.
rem ---------------------------------------------------------------------------

set "PYVER=3.12.10"
set "PYZIP=python-%PYVER%-embed-amd64.zip"
set "RT=%~dp0runtime"
set "PYEXE=%RT%\python.exe"
set "ZIP=%TEMP%\shengqiao-%PYZIP%"

if exist "%PYEXE%" goto run

echo.
echo  [ShengQiao] First run: downloading portable Python %PYVER% (about 11 MB, one time only)
echo.
call :fetch "https://registry.npmmirror.com/-/binary/python/%PYVER%/%PYZIP%"
if not exist "%PYEXE%" call :fetch "https://mirrors.huaweicloud.com/python/%PYVER%/%PYZIP%"
if not exist "%PYEXE%" call :fetch "https://www.python.org/ftp/python/%PYVER%/%PYZIP%"
del /q "%ZIP%" >nul 2>&1
if not exist "%PYEXE%" goto fallback
echo  [ShengQiao] Python is ready.
echo.

:run
copy /y "%~dp0app\python312._pth" "%RT%\python312._pth" >nul 2>&1
rem  "start.bat debug" runs in this window with all messages visible (for troubleshooting)
if /i "%~1"=="debug" goto debugrun
if /i "%~1"=="selftest" goto selftest
if not exist "%~dp0.shortcut-done" call :shortcuts
start "" "%RT%\pythonw.exe" "%~dp0app\server.py" %*
exit /b 0

:selftest
"%PYEXE%" "%~dp0app\selftest.py"
echo.
pause
exit /b 0

:debugrun
shift
"%PYEXE%" "%~dp0app\server.py" --no-tray %1 %2 %3 %4 %5 %6
if errorlevel 1 goto failed
exit /b 0

rem  Desktop + Start menu shortcut named ShengQiao in Chinese, with the app icon.
:shortcuts
set "SQ_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$n = [string][char]0x58F0 + [char]0x6865; $w = New-Object -ComObject WScript.Shell; foreach ($d in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) { $l = $w.CreateShortcut((Join-Path $d ($n + '.lnk'))); $l.TargetPath = Join-Path $env:SQ_DIR 'runtime\pythonw.exe'; $l.Arguments = '\"' + (Join-Path $env:SQ_DIR 'app\server.py') + '\"'; $l.WorkingDirectory = $env:SQ_DIR; $l.IconLocation = (Join-Path $env:SQ_DIR 'app\shengqiao.ico') + ',0'; $l.Description = 'ShengQiao'; $l.Save() }" >nul 2>&1
if not errorlevel 1 echo done> "%~dp0.shortcut-done"
exit /b 0

rem ---------------------------------------------------------------------------
rem  :fetch URL  - download + unzip into .\runtime, then verify the signature.
rem  curl/tar first (fast); PowerShell as a fallback (handles any path).
rem ---------------------------------------------------------------------------
:fetch
echo  - downloading %~1
del /q "%ZIP%" >nul 2>&1
set "SQ_URL=%~1"
curl.exe -L --fail --silent --show-error --retry 2 --connect-timeout 15 -o "%ZIP%" "%~1"
if errorlevel 1 del /q "%ZIP%" >nul 2>&1
if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -UseBasicParsing -Uri $env:SQ_URL -OutFile $env:ZIP } catch { exit 1 }"
if not exist "%ZIP%" exit /b 1
if exist "%RT%" rmdir /s /q "%RT%"
mkdir "%RT%"
tar.exe -xf "%ZIP%" -C "%RT%" >nul 2>&1
if not exist "%PYEXE%" powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Expand-Archive -Force -LiteralPath $env:ZIP -DestinationPath $env:RT } catch { exit 1 }"
if not exist "%PYEXE%" exit /b 1
rem  Skip the signature check only if PowerShell itself cannot run on this PC.
powershell -NoProfile -Command "exit 7" >nul 2>&1
if not errorlevel 7 exit /b 0
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = Get-AuthenticodeSignature -LiteralPath $env:PYEXE; if ($s.Status -eq 'NotSigned' -or $s.Status -eq 'HashMismatch' -or $s.SignerCertificate -eq $null -or $s.SignerCertificate.Subject -notmatch 'Python Software Foundation') { exit 1 }"
if errorlevel 1 goto badsig
exit /b 0

:badsig
echo  ! signature check failed for the file from this source, discarding it
rmdir /s /q "%RT%"
exit /b 1

rem ---------------------------------------------------------------------------
rem  Fallback: use a Python that is already installed (creates .venv once).
rem ---------------------------------------------------------------------------
:fallback
echo.
echo  [ShengQiao] Could not download portable Python. Trying the Python installed on this PC...
set "SYSPY="
py -3 -c "import sys" >nul 2>&1 && set "SYSPY=py -3"
if not defined SYSPY python -c "import sys" >nul 2>&1 && set "SYSPY=python"
if not defined SYSPY goto nopython
set "VPY=%~dp0.venv\Scripts\python.exe"
if not exist "%VPY%" %SYSPY% -m venv "%~dp0.venv"
if not exist "%VPY%" goto nopython
if exist "%~dp0.venv\deps-ok.txt" goto runvenv
"%VPY%" -m pip install --disable-pip-version-check -r "%~dp0app\requirements.txt" -i https://pypi.tuna.tsinghua.edu.cn/simple
if errorlevel 1 "%VPY%" -m pip install --disable-pip-version-check -r "%~dp0app\requirements.txt"
if errorlevel 1 goto failed
echo ok> "%~dp0.venv\deps-ok.txt"
:runvenv
set "VPYW=%~dp0.venv\Scripts\pythonw.exe"
if /i "%~1"=="debug" goto venvdebug
start "" "%VPYW%" "%~dp0app\server.py" %*
exit /b 0
:venvdebug
"%VPY%" "%~dp0app\server.py" --no-tray
if errorlevel 1 goto failed
exit /b 0

:nopython
echo.
echo  [ShengQiao] Python could not be downloaded and no installed Python was found.
echo.
echo  Fix: check the internet connection and run start.bat again.
echo  Or download this file in a browser:
echo      https://www.python.org/ftp/python/%PYVER%/%PYZIP%
echo  create a folder named "runtime" next to start.bat, unzip the file's contents
echo  into it (python.exe must end up directly inside "runtime"), then run start.bat again.
echo.
pause
exit /b 1

:failed
echo.
echo  [ShengQiao] The program stopped with an error (see the messages above).
echo.
pause
exit /b 1
