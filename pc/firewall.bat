@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem  Allows phones on the local network to reach ShengQiao through Windows Firewall.
rem  Needs administrator rights; it asks for them automatically.

fltmc >nul 2>&1
if not errorlevel 1 goto admin
echo Requesting administrator permission...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
exit /b

:admin
rem  Clicking Cancel on the firewall prompt creates BLOCK rules for the program,
rem  and block rules win over allow rules, so remove them first, then allow.
call :allow "%~dp0runtime\pythonw.exe"
call :allow "%~dp0runtime\python.exe"
call :allow "%~dp0.venv\Scripts\pythonw.exe"
call :allow "%~dp0ShengQiao.exe"

:portrule
netsh advfirewall firewall delete rule name="ShengQiao (port)" >nul 2>&1
netsh advfirewall firewall add rule name="ShengQiao (port)" dir=in action=allow protocol=TCP localport=23333-23342 profile=any >nul
netsh advfirewall firewall delete rule name="ShengQiao (bonjour)" >nul 2>&1
netsh advfirewall firewall add rule name="ShengQiao (bonjour)" dir=in action=allow protocol=UDP localport=5353 profile=private,domain >nul
if errorlevel 1 goto fail
echo Done. Phones on the same network can now connect to ShengQiao.
echo.
pause
exit /b 0

:fail
echo Failed to add the firewall rule.
echo.
pause
exit /b 1

:allow
if not exist "%~1" exit /b 0
netsh advfirewall firewall delete rule name=all program="%~1" >nul 2>&1
netsh advfirewall firewall add rule name="ShengQiao" dir=in action=allow program="%~1" enable=yes profile=any >nul
exit /b 0
