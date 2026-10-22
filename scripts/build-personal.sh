#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
if [[ ! -f signing.properties ]]; then
    echo 'Create signing.properties with your existing private signing key; see README.md.' >&2
    exit 1
fi
./gradlew :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk artifacts/orgenda-0.1.3.apk
shasum -a 256 artifacts/orgenda-0.1.3.apk > artifacts/orgenda-0.1.3.apk.sha256
