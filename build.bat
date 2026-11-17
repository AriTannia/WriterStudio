@echo off
cd /d %~dp0
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 --release 17 -cp "lib\*" -d out src\writerstudio\*.java || exit /b 1
echo Main-Class: writerstudio.Main> out\manifest.txt
for %%J in (lib\*.jar) do (pushd out & jar xf "..\%%J" & popd)
if exist out\META-INF\MANIFEST.MF del out\META-INF\MANIFEST.MF
jar cfm WriterStudio.jar out\manifest.txt -C out writerstudio -C out org -C out com -C out META-INF
echo Built WriterStudio.jar
