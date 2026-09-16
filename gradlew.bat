@rem Generic Gradle wrapper (Windows).
@echo off
setlocal

set DIRNAME=%~dp0
set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

if exist "%WRAPPER_JAR%" (
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: java is required to use the Gradle wrapper.
        exit /b 1
    )
    java -Xmx64m -Dfile.encoding=UTF-8 -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo ERROR: neither the Gradle wrapper nor gradle are available.
        exit /b 1
    )
    gradle %*
)