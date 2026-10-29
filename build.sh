#!/usr/bin/env bash
set -eu

cd "$(dirname "$(readlink -f "$0")")"

printf '\n============================================================\n'
printf ' NickSyncX - clean build\n'
printf '============================================================\n'
printf 'Project: %s\n\n' "$PWD"

if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v java >/dev/null 2>&1; then
    printf '[ERROR] Java was not found in PATH. Install JDK 21 or newer and set JAVA_HOME.\n' >&2
    exit 1
fi

printf '[INFO] Java version:\n'
java -version 2>&1

if [ -x ./gradlew ]; then
    GRADLE_CMD=./gradlew
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=gradle
else
    printf '[ERROR] Gradle was not found in PATH and no gradlew script exists.\n' >&2
    printf 'Install Gradle 9.8 or add Gradle to PATH.\n' >&2
    exit 2
fi

printf '\n[INFO] Running clean build. The first run may download dependencies.\n'
if ! "$GRADLE_CMD" --no-daemon clean build --stacktrace >build-log.txt 2>&1; then
    printf '\n[BUILD FAILED] Gradle log follows:\n'
    printf '%s\n' '------------------------------------------------------------'
    cat build-log.txt
    printf '%s\n' '------------------------------------------------------------'
    printf 'No output JARs were copied to dist.\n'
    exit 3
fi

PAPER_JAR=paper/build/libs/paper-1.5.0.jar
VELOCITY_JAR=velocity/build/libs/velocity-1.5.0.jar
API_JAR=api/build/libs/NickSyncX-API-1.5.0.jar

for artifact in "$PAPER_JAR" "$VELOCITY_JAR" "$API_JAR"; do
    if [ ! -f "$artifact" ]; then
        printf '[ERROR] Expected build artifact is missing: %s\n' "$artifact" >&2
        printf 'See build-log.txt.\n' >&2
        exit 4
    fi
done

mkdir -p dist
cp "$PAPER_JAR" dist/NickSyncX-Paper-1.5.0.jar
cp "$VELOCITY_JAR" dist/NickSyncX-Velocity-1.5.0.jar
cp "$API_JAR" dist/NickSyncX-API-1.5.0.jar

printf '\n============================================================\n'
printf ' BUILD SUCCESSFUL\n'
printf '============================================================\n'
printf 'Paper:   dist/NickSyncX-Paper-1.5.0.jar\n'
printf 'Velocity: dist/NickSyncX-Velocity-1.5.0.jar\n'
printf 'API:     dist/NickSyncX-API-1.5.0.jar (developer compile-time dependency)\n'
printf 'Build log: build-log.txt\n\n'
printf 'Install the Paper JAR on each backend and the shaded Velocity JAR on the proxy.\n'
printf 'Back up plugins/NickSyncX before upgrading.\n'