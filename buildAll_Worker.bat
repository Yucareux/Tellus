@echo off & setlocal enabledelayedexpansion

set "VERSION=%~1"
set "ROOT=%~2"
set "WORK_DIR=%~3"
set "WORKER=%WORK_DIR%\%VERSION%"
set "JAR_OUTPUT_DIR=%~4"

REM remove the ending "\" from the root folder, otherwise the final quote
REM in the robocopy command will be escaped and it won't run
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "WORKER=%~3\%~1"

set "FORGIX_BUILT_JAR_DIR=%WORKER%\build\forgix"
set "FORGE_17_BUILT_JAR_DIR=%WORKER%\forge17\build\libs"
set "FORGE_12_BUILT_JAR_DIR=%WORKER%\forge112\build\libs"



echo ==================== [%VERSION%] Copying workspace ====================
mkdir "%WORKER%"
robocopy "%ROOT%" "%WORKER%" /E /XD "%WORKER%" "_buildWorkers" "buildAllJars" ".gradle" "build" ".git" ".idea" ".gitlab" "run" "testScripts" /NFL /NDL

echo ==================== [%VERSION%] Cleaning ====================
cd /d "%WORKER%"
call .\gradlew.bat clean 
REM optional arg that can be added if we want to log the result to a file
REM >"%WORK_DIR%\build_%VERSION%.log" 2>&1

echo ==================== [%VERSION%] Assembling ====================
call .\gradlew.bat assemble -PmcVer="%VERSION%" 
REM optional arg that can be added if we want to log the result to a file
REM >>"%WORK_DIR%\build_%VERSION%.log" 2>&1

echo ==================== [%VERSION%] Exporting ====================
mkdir "%JAR_OUTPUT_DIR%"
robocopy "%FORGIX_BUILT_JAR_DIR%" "%JAR_OUTPUT_DIR%" /NFL /NDL
robocopy "%FORGE_17_BUILT_JAR_DIR%" "%JAR_OUTPUT_DIR%" /XF "*-dev.jar" "*-all.jar" "*-sources.jar" /NFL /NDL
robocopy "%FORGE_12_BUILT_JAR_DIR%" "%JAR_OUTPUT_DIR%" /XF "*-dev.jar" "*-all.jar" "*-sources.jar" /NFL /NDL

echo ==================== [%VERSION%] Done ====================
endlocal

REM can be uncommented for debugging
REM pause