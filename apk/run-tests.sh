#!/usr/bin/env bash
# Convenience wrapper for the JVM unit tests.
#
#   ./run-tests.sh                     # :app:testDebugUnitTest
#   ./run-tests.sh :app:someOtherTask  # anything else
#
# ── You probably do not need this script ─────────────────────────────────────────────────────────
# It used to exist because unit tests could not run on Windows at all without a specific environment
# variable being exported first. That is now handled inside `gradlew` / `gradlew.bat` themselves, so
# a plain `./gradlew :app:testDebugUnitTest` works from a fresh clone with nothing exported.
#
# The full write-up of the defect — an unquoted `-Djava.library.path` composed from the daemon JVM's
# own value, and the measurements showing why it cannot be fixed from the Test task or from
# `org.gradle.jvmargs` — is in the comment block in `gradlew`. Read that before changing either file.
#
# This script is kept only so the muscle memory and CI references keep working.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -eq 0 ]; then
  set -- :app:testDebugUnitTest
fi

exec ./gradlew "$@"
