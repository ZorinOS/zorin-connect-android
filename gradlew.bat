@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Default JVM options (can be customized by users)
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Check if JAVA_HOME is set, otherwise search for java.exe in PATH
if defined JAVA_HOME (
    set JAVA_HOME=%JAVA_HOME:"=%
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    if exist "%JAVA_EXE%" goto init
    echo.
    echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
    echo Please set the JAVA_HOME variable to your Java installation directory.
    goto fail
)

@rem Fall back to java.exe if JAVA_HOME is not set
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo Please set the JAVA_HOME variable to your Java installation directory.
goto fail

:init
@rem Capture command-line arguments
set CMD_LINE_ARGS=%*

@rem Set up the classpath for the Gradle Wrapper
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle with the specified JVM options and arguments
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
@rem Check if script succeeded; if not, allow exit code handling
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
@rem If GRADLE_EXIT_CONSOLE is set, script returns the code instead of cmd.exe
if not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
@rem Close local scope if Windows NT
if "%OS%"=="Windows_NT" endlocal

:omega
