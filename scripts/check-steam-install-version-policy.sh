#!/usr/bin/env bash
# Fast JVM regression suite for the version policy; no Android SDK, account or provider required.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Match gradle/libs.versions.toml. Downloads are fixed tool/test dependencies, never game data.
if [[ -n "${KOTLINC:-}" ]]; then
  compiler="$KOTLINC"
else
  curl --fail --silent --show-error --location --retry 2 \
    'https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-2.1.21.zip' \
    --output "$work/kotlin.zip"
  unzip -q "$work/kotlin.zip" -d "$work"
  compiler="$work/kotlinc/bin/kotlinc"
fi
curl --fail --silent --show-error --location --retry 2 \
  'https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar' --output "$work/junit.jar"
curl --fail --silent --show-error --location --retry 2 \
  'https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar' --output "$work/hamcrest.jar"
"$compiler" \
  app/src/main/java/app/gamenative/utils/SteamInstallVersionPolicy.kt \
  app/src/test/java/app/gamenative/utils/SteamInstallVersionPolicyTest.kt \
  -cp "$work/junit.jar:$work/hamcrest.jar" -include-runtime -d "$work/tests.jar"
java -cp "$work/tests.jar:$work/junit.jar:$work/hamcrest.jar" org.junit.runner.JUnitCore \
  app.gamenative.utils.SteamInstallVersionPolicyTest

# Both surfaces must keep using the central policy rather than reimplementing Store comparisons.
python3 - <<'PY'
from pathlib import Path
root = Path('app/src/main/java/app/gamenative')
service = (root / 'service/SteamService.kt').read_text()
launch = (root / 'ui/PluviaMain.kt').read_text()
screen = (root / 'ui/screen/library/appscreen/SteamAppScreen.kt').read_text()
acf = (root / 'utils/SteamUtils.kt').read_text()
assert 'SteamInstallVersionPolicy.isStoreUpdatePending(installed, advertised, pins)' in service
assert 'SteamService.isUpdatePending(gameId, branch)' in launch
assert 'SteamService.isUpdatePending(libraryItem.gameId, branch)' in screen
assert 'SteamInstallVersionPolicy.installedDepotsAcf(clientDepots)' in acf
assert 'File(steamappsDir.parentFile, "depotcache")' in acf
assert 'SteamManifestOverridesChanged' in screen
print('PASS: update-button, launch gate, ACF and source-change integration checks')
PY
