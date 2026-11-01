@echo off & setlocal enabledelayedexpansion


echo ==================== Getting versions to build... ====================
mkdir _buildAllJars 2>nul
del _buildAllJars\* /Q 2>nul

set "ROOT=%~dp0"
set "WORK_DIR=%ROOT%_buildWorkers"
mkdir "%WORK_DIR%" 2>nul



echo ==================== Clearing old workers... ====================
REM clearing the old worker folders is to make sure any source files that were deleted
REM are correctly deleted here before build
for /d %%D in ("_buildWorkers\*") do (
	rd /s /q "%%D"
)



REM get the number of versions to compile
set count=0
for %%f in (versionProperties\*) do set /a count+=1
echo ==================== Found %count% versions to build in parallel ====================

REM Launch a parallel job for each version
for %%f in (%ROOT%versionProperties\*) do (
    set version=%%~nf
	
	echo starting [!version!]...
    start "Build !version!" cmd /c ""%ROOT%buildAll_Worker.bat" "!version!" "%ROOT%" "%WORK_DIR%" ""..\..\_buildAllJars"""
	
	REM Minor timeout between launches so we can stop the build early if we only want 
	REM to test part of the script and to reduce startup load
	timeout /t 1 /nobreak
	
REM 2>nul to supress a harmless warning that the for loop
REM "cannot find the drive specified"
) 2>nul


echo ==================== All builds started... Completed Jars will be in _buildAllJars ====================
endlocal