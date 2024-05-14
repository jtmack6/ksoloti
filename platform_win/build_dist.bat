rem --- Builds bundle and runtime

@echo off

call build.bat

setlocal
set JAVA_HOME=

:getjdklocation
rem Resolve location of Java JDK environment

set KeyName=HKEY_LOCAL_MACHINE\SOFTWARE\JavaSoft\Java Development Kit
set Cmd=reg query "%KeyName%" /s
for /f "tokens=2*" %%i in ('%Cmd% ^| find "JavaHome"') do set JAVA_HOME=%%j

if not defined JAVA_HOME (
   echo JDK not installed, please install JDK first
   echo Azul Zulu JDK 21.30.15 (21.0.1) is recommended: https://www.azul.com/core-post-download/?endpoint=zulu&uuid=d7b2d714-9065-45cb-b9b4-aa286b5a8f81
   pause
   goto :end
)

echo JAVA_HOME: %JAVA_HOME%

set ANT=%~dp0\apache-ant-1.10.14\bin\ant.bat

echo ANT: %ANT%

if not exist %ANT% (
   echo ANT not found, please run build.bat first
   pause
   goto :end
)

cd %~dp0\..

set PATH=%PATH%;%~dp0\bin;C:\Program Files (x86)\WiX Toolset v3.9\bin

%ANT% -Dbuild.bundle=true bundle
%ANT% -Dbuild.runtime=true runtime

:end
endlocal
