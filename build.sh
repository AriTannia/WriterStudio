#!/bin/sh
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -encoding UTF-8 --release 17 -cp "lib/*" -d out src/writerstudio/*.java
printf 'Main-Class: writerstudio.Main\n' > out/manifest.txt
for jar_file in lib/*.jar; do (cd out && jar xf "../$jar_file"); done
rm -f out/META-INF/MANIFEST.MF
jar cfm WriterStudio.jar out/manifest.txt -C out writerstudio -C out org -C out com -C out META-INF
echo "Built WriterStudio.jar  ->  java -jar WriterStudio.jar"
