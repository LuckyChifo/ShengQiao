@rem Gradle wrapper for Windows
@echo off
setlocal
set APP_HOME=%~dp0
if defined JAVA_HOME goto javaHome
set JAVA_EXE=java.exe
goto run
:javaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
:run
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
