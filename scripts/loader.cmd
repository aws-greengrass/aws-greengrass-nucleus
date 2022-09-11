@REM -------------------------------
@REM Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
@REM SPDX-License-Identifier: Apache-2.0
@REM -------------------------------
@echo off
SETLOCAL EnableDelayedExpansion
SET DIR=%~dp0

rem Bypass "Terminate Batch Job" prompt.
if "%~1"=="-FIXED_CTRL_C" (
   REM Remove the -FIXED_CTRL_C parameter
   SHIFT
) ELSE (
   REM Run the batch with <NUL and -FIXED_CTRL_C
   CALL <NUL %0 -FIXED_CTRL_C %*
   GOTO :EOF
)

@REM Get the root GG directory (generally /greengrass/v2)
FOR %%I IN ("%DIR%\..\..\..\..") DO SET "GG_ROOT=%%~fI"
SET LAUNCH_DIR=%GG_ROOT%\alts\current
@REM SET CONFIG_FILE=""

SET IS_SYMLINK=0
CALL :directory_is_symlink "%GG_ROOT%\alts\new" IS_SYMLINK
IF %IS_SYMLINK% EQU 1 (
    CALL :directory_is_symlink "%GG_ROOT%\alts\old" IS_SYMLINK
    IF !IS_SYMLINK! EQU 0 (
        CALL :directory_is_symlink "%LAUNCH_DIR%" IS_SYMLINK
        IF !IS_SYMLINK! EQU 1 (
            CALL :flip_links "%LAUNCH_DIR%" "%GG_ROOT%\alts\old"
        )
    )
    CALL :flip_links "%GG_ROOT%\alts\new" "%LAUNCH_DIR%"
)

CALL :directory_is_symlink "%GG_ROOT%\alts\broken" IS_SYMLINK
IF !IS_SYMLINK! EQU 1 (
    CALL :directory_is_symlink "%GG_ROOT%\alts\old" IS_SYMLINK
    IF !IS_SYMLINK! EQU 1 (
        CALL :flip_links "%GG_ROOT%\alts\old" "%LAUNCH_DIR%"
    )
)

CALL :directory_is_symlink "%GG_ROOT%\alts\old" IS_SYMLINK
IF !IS_SYMLINK! EQU 1 (
    CALL :directory_is_symlink "%LAUNCH_DIR%" IS_SYMLINK
    IF !IS_SYMLINK! EQU 0 (
        CALL :flip_links "%GG_ROOT%\alts\old" "%LAUNCH_DIR%"
    )
)

@REM EXIST works for files, directories, and symlink dirs
IF NOT EXIST "%LAUNCH_DIR%" (
    ECHO FATAL: No Nucleus found!
    EXIT /B 1
)

@REM Get JVM_OPTIONS from launch.params if it exists
IF EXIST "%LAUNCH_DIR%\launch.params" (
    FOR /F "usebackq delims=" %%A IN ("%LAUNCH_DIR%\launch.params") DO SET JVM_OPTIONS=%%A
)

SET JVM_OPTIONS=%JVM_OPTIONS% -Droot="%GG_ROOT%"
SET OPTIONS=--setup-system-service false

REM enable extensions so we can use "IF DEFINED"
SETLOCAL ENABLEEXTENSIONS

IF DEFINED GG_JAVA_EXE (
    SET JAVA_EXE=%GG_JAVA_EXE%
) ELSE (
    SET JAVA_EXE=java
)

ECHO Java executable: %JAVA_EXE%
ECHO JVM options: %JVM_OPTIONS%
ECHO Nucleus options: %OPTIONS%
SET /A MAX_RETRIES=3
@REM Attempt to start the nucleus 3 times
FOR /L %%i IN (1,1,%MAX_RETRIES%) DO (
    "%JAVA_EXE%" -Dlog.store=FILE %JVM_OPTIONS% -jar "%LAUNCH_DIR%\distro\lib\Greengrass.jar" %OPTIONS%
    SET KERNEL_EXIT_CODE=!ERRORLEVEL!

    IF !KERNEL_EXIT_CODE! EQU 0 (
        ECHO Restarting Nucleus
        call "%LAUNCH_DIR%\distro\bin\loader.cmd"
        EXIT /B !ERRORLEVEL!
    ) ELSE (
    IF !KERNEL_EXIT_CODE! EQU 100 (
        ECHO Restarting Nucleus
        call "%LAUNCH_DIR%\distro\bin\loader.cmd"
        EXIT /B !ERRORLEVEL!
    ) ELSE (
    IF !KERNEL_EXIT_CODE! EQU 101 (
        ECHO Rebooting host
        SHUTDOWN /R
        EXIT /B 0
    ) ELSE (
    rem normal exit code when using ctrl+c
    IF !KERNEL_EXIT_CODE! EQU 130 (
        echo Stopping
        EXIT /B 0
    ) ELSE (
    rem normal exit code when using ctrl+c
    IF !KERNEL_EXIT_CODE! EQU -1073741510 (
        echo Stopping
        EXIT /B 0
    ) ELSE (
        ECHO Nucleus exited !KERNEL_EXIT_CODE!. Attempt %%i out of %MAX_RETRIES%
    )))))
)

CALL :directory_is_symlink "%GG_ROOT%\alts\old" IS_SYMLINK
IF !IS_SYMLINK! EQU 1 (
    CALL :directory_is_symlink "%LAUNCH_DIR%" IS_SYMLINK
    IF !IS_SYMLINK! EQU 1 (
        CALL :flip_links "%LAUNCH_DIR%" "%GG_ROOT%\alts\broken"
        CALL :flip_links "%GG_ROOT%\alts\old" "%LAUNCH_DIR%"
    )
)

EXIT /B !KERNEL_EXIT_CODE!

@REM ==========================================================
@REM ================== FUNCTION DEFINITIONS ==================
@REM ==========================================================

@REM Checks if directory is a symlink by checking its attributes
@REM @param1 directory to test
@REM @param2 out variable (pass by reference)
@REM    1 = @param1 is a symlinked directory
@REM    0 = @param1 is NOT a symlink
:directory_is_symlink
SET RETURN_VALUE=0
if EXIST %1 (
    FOR %%I IN (%1) DO SET attribs=%%~aI
    @REM The 8th element of directory attributes indicate if it is a reparse point (symlink)
    if "!attribs:~8,1!" EQU "l" (
        if "!attribs:~0,1!" EQU "d" (
            SET RETURN_VALUE=1
        )
    ) ELSE (
        SET RETURN_VALUE=0
    )
)
SET %~2=%RETURN_VALUE%
EXIT /B 0

@REM Gets the source of symlink @param1 and points symlink @param2 to that source
@REM Removes @param1 symlink
:flip_links
@REM Delete symlink @param2 because it will be overwritten with the source of @param1
RMDIR %2 2>NUL || :

@REM Split the parent directory from fully qualified path and file name
FOR %%I IN (%1) DO SET PARENT_DIR=%%~dpI && SET DIR_NAME=%%~nxI

@REM Get linked dir information using some regex
@REM Example output:
@REM    04/08/2021  05:19 PM    <SYMLINKD>     current [C:\greengrass\v2\alts\init]
FOR /F "delims=" %%I IN ('DIR /A:L %PARENT_DIR% ^| FINDSTR /R /C:"\<SYMLINKD\>[ ]*%DIR_NAME%[ ]*\[.*\]$"') DO SET DIR_OUT=%%I
@REM Get second token (B starting from A) when considering [] as delimiters
FOR /F "tokens=1,2 delims=[]" %%A IN ("%DIR_OUT%") DO SET SOURCE_FILE=%%B

@REM Create new symlink with @param2's name and point towards @param1's source
MKLINK /D %2 "%SOURCE_FILE%" >NUL

@REM Remove @param1
RMDIR %1 2>NUL || :
EXIT /B 0
