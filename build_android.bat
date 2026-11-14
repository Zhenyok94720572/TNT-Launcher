@echo off
rem Builds the Android APK of TNT Launcher (based on Amethyst / PojavLauncher).
rem Needs: JDK 17, Android SDK (platform 37, build-tools 37, NDK 27.3.13750724).
rem ndk-build cannot work in a path with spaces, so the project is built through C:\tntbuild junction.
setlocal
set TOOLS=%USERPROFILE%\tnt-android-tools
if not defined JAVA_HOME set JAVA_HOME=%TOOLS%\jdk\jdk-17.0.21+9
set PATH=%TOOLS%\git\cmd;%JAVA_HOME%\bin;%PATH%
if not exist C:\tntbuild mklink /J C:\tntbuild "%~dp0android"
pushd C:\tntbuild
call gradlew.bat :app_pojavlauncher:assembleDebug --console=plain
set ERR=%ERRORLEVEL%
popd
if %ERR% neq 0 exit /b %ERR%
copy /y "%~dp0android\app_pojavlauncher\build\outputs\apk\debug\app_pojavlauncher-debug.apk" "%~dp0TNTLauncher.apk" >nul
echo APK: %~dp0TNTLauncher.apk
