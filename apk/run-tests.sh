#!/usr/bin/env bash
# Run the JVM unit tests. USE THIS instead of `./gradlew test` on Windows.
#
# Why this exists
# ---------------
# Gradle launches each test worker with an UNQUOTED `-Djava.library.path=<value>` argument, where the
# value is derived from the environment PATH. This happens inside Gradle's worker launcher, below the
# Test task API — the property appears in neither `systemProperties` nor `jvmArgs` nor `allJvmArgs`,
# so it cannot be removed, overridden, or filtered from build.gradle.kts (all four approaches were
# tried). Setting it in gradle.properties does not help either, because the value is read from the
# environment rather than from the daemon's own system property.
#
# On a PATH containing "C:\Program Files\...", java.exe splits the argument at the first space,
# receives `-Djava.library.path=C:\Program`, and treats the remainder as the main class:
#
#   Error: Could not find or load main class Files\Git\bin;C:\Program
#
# The reported "class name" changes between runs because it is simply whatever PATH fragment happens
# to follow a space. The effect is that NO unit test can run at all — which is how this project came
# to have tests that compile but had never once been executed.
#
# The fix is to invoke Gradle with a PATH whose every entry is space-free, using 8.3 short names.
# Everything the build needs (the JDK, System32, the Git shell utilities) is reachable that way.
set -euo pipefail
cd "$(dirname "$0")"

# 8.3 short form of JAVA_HOME, so the JDK path carries no space either.
# cygpath -d does this natively; shelling out to cmd for %~sI gets mangled by the shell's own
# path conversion and yields a doubly-quoted, invalid path.
JH_SHORT="$(cygpath -d "${JAVA_HOME:?JAVA_HOME must be set}")"
JH_UNIX="$(cygpath -u "$JH_SHORT")"

export JAVA_HOME="$JH_SHORT"
export PATH="$JH_UNIX/bin:/c/Windows/System32:/c/Windows:/usr/bin:/bin"

exec ./gradlew "${@:-:app:testDebugUnitTest}"
