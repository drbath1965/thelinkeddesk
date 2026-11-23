#!/bin/sh
# Builds LinkedDesk.jar and runs the tests, with only the JDK (Java 21+). No downloads needed.
set -e
cd "$(dirname "$0")"
rm -rf out out-test && mkdir -p out out-test
javac --release 21 -encoding UTF-8 -d out $(find src -name '*.java')
(cd src && find . -type f ! -name '*.java' -exec cp --parents {} ../out/ \;)
printf 'Main-Class: com.linkeddesk.app.App\n' > out/MANIFEST.MF
jar --create --file LinkedDesk.jar --manifest out/MANIFEST.MF -C out com
echo "Built LinkedDesk.jar"
if [ "$1" != "--skip-tests" ]; then
  javac --release 21 -encoding UTF-8 -cp out -d out-test $(find test -name '*.java')
  java -cp out:out-test com.linkeddesk.TestMain
fi
