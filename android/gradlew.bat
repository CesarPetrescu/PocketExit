@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
set SUMFILE=%APP_HOME%gradle\wrapper\gradle-wrapper.jar.sha256
set URL=https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar

if not exist "%JAR%" (
  echo Bootstrapping verified Gradle 8.13 wrapper JAR...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%JAR%.tmp'" || exit /b 1
  move /Y "%JAR%.tmp" "%JAR%" >nul || exit /b 1
)

for /f "usebackq tokens=*" %%A in ("%SUMFILE%") do set EXPECTED=%%A
for /f "usebackq tokens=*" %%A in (`powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%JAR%').Hash.ToLowerInvariant()"`) do set ACTUAL=%%A
if /I not "%EXPECTED%"=="%ACTUAL%" (
  echo Gradle wrapper JAR checksum mismatch 1>&2
  del /Q "%JAR%" 2>nul
  exit /b 1
)

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
