#!/usr/bin/env bash
# Build Hearth's APK in the pinned toolchain container.
#
#   scripts/docker-build.sh                      # :app:assemblePlayQa
#   scripts/docker-build.sh :app:assembleFossQa  # any Gradle task(s)
#   VERIFY=1 scripts/docker-build.sh             # then scripts/verify-release.py
#
# The image is linux/amd64 because the Android NDK has no linux-aarch64 build,
# so on Apple Silicon the native compile runs emulated and is slow. Gradle and
# SDK caches live in named volumes, so only the first run pays full cost.
set -euo pipefail

image=${IMAGE:-hearth-build}
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tasks=${*:-:app:assemblePlayQa}

if ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "==> Building $image (one time, several GB: NDK is most of it)"
  docker build --platform=linux/amd64 \
    -f "$repo/docker/android-build.Dockerfile" -t "$image" "$repo"
fi

command=(./gradlew --no-daemon $tasks)
if [[ ${VERIFY:-0} == 1 ]]; then
  command+=(\&\& python3 scripts/verify-release.py)
fi

echo "==> Running: ${command[*]}"
docker run --rm --platform=linux/amd64 \
  -v "$repo:/workspace" \
  -v hearth-gradle:/gradle \
  -e GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g" \
  "$image" "${command[*]}"

echo "==> APKs on the host:"
find "$repo/app/build/outputs/apk" -name '*.apk' -print 2>/dev/null || echo "  none produced"
