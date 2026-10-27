#!/bin/sh
# Download the Alloy 6.2.0 distribution jar from Maven Central and verify it.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
url=https://repo.maven.apache.org/maven2/org/alloytools/org.alloytools.alloy.dist/6.2.0/org.alloytools.alloy.dist-6.2.0.jar
expected=f399311928e4e9f5cc8a6c09facc36c6dd4f4b9c
curl -fsSL -o "$here/alloy.jar" "$url"
actual=$(sha1sum "$here/alloy.jar" | cut -d' ' -f1)
if [ "$actual" != "$expected" ]; then
  echo "SHA-1 mismatch: expected $expected, got $actual" >&2
  rm -f "$here/alloy.jar"
  exit 1
fi
echo "alloy.jar verified ($actual)"
