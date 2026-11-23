#!/bin/sh
# Same as build-installer.bat for macOS/Linux: a ready-to-run folder with Java included.
set -e
cd "$(dirname "$0")"
./build.sh --skip-tests
rm -rf dist && mkdir -p dist/input && cp LinkedDesk.jar dist/input/
jpackage --type app-image --name TheLinkedDesk --app-version 1.5.0 --input dist/input --main-jar LinkedDesk.jar \
  --main-class com.linkeddesk.app.App --java-options -Xmx2g \
  --add-modules java.base,java.desktop,java.net.http,java.xml,java.logging,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs \
  --dest dist
echo "Built dist/TheLinkedDesk"
