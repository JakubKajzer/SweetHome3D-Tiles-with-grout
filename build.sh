#!/bin/sh
# Użycie: ./build.sh /sciezka/do/SweetHome3D.jar
# (znajdziesz: find / -name 'SweetHome3D*.jar' 2>/dev/null)
set -e
SH3D_JAR="${1:?podaj sciezke do SweetHome3D.jar}"
rm -rf build TilesPlugin.sh3p
mkdir -p build
javac -encoding UTF-8 --release 8 -cp "$SH3D_JAR" -d build src/TilesPlugin.java
cp src/ApplicationPlugin.properties build/
(cd build && jar cf ../TilesPlugin.sh3p .)
echo "Gotowe: TilesPlugin.sh3p"
