#!/usr/bin/env bash
# Convenience wrapper: runs the JVM unit tests with the one environment variable they need on Windows.
#
# ── The problem (root-caused 2026-07-31) ─────────────────────────────────────────────────────────
# Gradle launches each test worker with an UNQUOTED `-Djava.library.path=<value>`. AGP builds that
# value from the DAEMON JVM's own java.library.path — which Windows derives from PATH — and appends the
# unit-test jniLibs directories. With "C:\Program Files\..." in PATH, java.exe splits the argument at
# the first space and treats the remainder as the main class:
#
#   Error: Could not find or load main class Files\Git\bin;C:\Program
#
# The reported class name varies per run because it is whichever PATH fragment follows a space. Net
# effect: no unit test runs at all.
#
# ── Why the obvious fixes do not work ────────────────────────────────────────────────────────────
# Setting it in gradle.properties is silently discarded: Gradle STRIPS -Djava.library.path from
# org.gradle.jvmargs. Confirmed by reading the daemon's own command line under --debug, which showed
# only `-Xmx2048m -Dfile.encoding=UTF-8 -Duser.country=MY ...`. Setting it on the Test task does not
# work either — the property is not in systemProperties, jvmArgs, or allJvmArgs at execution time,
# because AGP composes the worker argument after the task's own configuration.
#
# ── The fix ──────────────────────────────────────────────────────────────────────────────────────
# JDK_JAVA_OPTIONS is prepended by the `java` launcher itself (JDK 9+), so Gradle cannot filter it. The
# daemon therefore starts with a clean java.library.path, and AGP copies that clean value into every
# worker. `./gradlew test` then works normally.
#
# Export JDK_JAVA_OPTIONS in CI and in your IDE's Gradle settings and you will not need this script;
# it exists so that a fresh clone can run the tests without knowing any of the above.
#
# ── Why this also restarts the daemon (found again 2026-08-01) ────────────────────────────────────
# Setting the variable is necessary but not sufficient. Gradle REUSES a running daemon, and the
# daemon's java.library.path is fixed at ITS startup — so if any earlier command started the daemon
# without this variable (a plain `./gradlew assembleRelease`, a pre-commit `lintDebug` hook, or the
# IDE), AGP copies that daemon's polluted value into every test worker and the tests fail exactly as
# described above, even though this script set the variable correctly.
#
# So: run, and if the tell-tale error appears, stop the daemons and retry once against a fresh one.
# The fast path is unaffected when the daemon is already clean, which is the common case.
set -euo pipefail
cd "$(dirname "$0")"

# Any space-free directory works — the value only has to be well-formed. Native libraries that tests
# genuinely need are added separately by AGP as jniLibs paths.
export JDK_JAVA_OPTIONS="-Djava.library.path=C:\Windows\System32"

if [ "$#" -eq 0 ]; then
  set -- :app:testDebugUnitTest
fi

# The marker is the JVM launcher failing to parse the unquoted -Djava.library.path, which surfaces as
# whichever PATH fragment followed a space being treated as the main class.
POLLUTED_DAEMON='Could not find or load main class'

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

# PIPESTATUS[0] rather than the pipeline's own status: `tee` succeeds regardless, and relying on
# pipefail here is exactly the sort of thing that silently stops working.
set +e
./gradlew "$@" 2>&1 | tee "$log"
status="${PIPESTATUS[0]}"
set -e

if [ "$status" -eq 0 ]; then
  exit 0
fi

if grep -q "$POLLUTED_DAEMON" "$log"; then
  echo
  echo "run-tests.sh: the reused Gradle daemon has a polluted java.library.path."
  echo "run-tests.sh: stopping daemons and retrying once against a fresh one..."
  ./gradlew --stop
  exec ./gradlew "$@"
fi

# A genuine test or compile failure — surface it unchanged rather than retrying and hiding it.
exit 1
