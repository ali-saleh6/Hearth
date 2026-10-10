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

# /root/.android holds the debug keystore. Without a volume, --rm throws it
# away and every build is signed with a fresh random key, so installing over a
# previous build fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
# gradle.properties asks for -Xmx6g, which the Docker VM cannot honour next to a
# separate Kotlin daemon: the kernel OOM-kills the build daemon mid-R8. A
# command-line -D overrides gradle.properties, so bound both JVMs to fit.
# --no-watch-fs: Gradle's native file watcher cannot poll a macOS bind mount.
gradle="./gradlew --no-daemon --no-watch-fs"
gradle+=" -Dorg.gradle.jvmargs='-Xmx4g -Dfile.encoding=UTF-8'"
gradle+=" -Dkotlin.daemon.jvmargs=-Xmx1536m"

command="$gradle $tasks"
if [[ ${VERIFY:-0} == 1 ]]; then
  command+=" && python3 scripts/verify-release.py"
fi

echo "==> Running: $command"
docker run --rm --platform=linux/amd64 \
  -v "$repo:/workspace" \
  -v hearth-gradle:/gradle \
  -v hearth-android-home:/root/.android \
  "$image" "$command"

echo "==> APKs on the host:"
find "$repo/app/build/outputs/apk" -name '*.apk' -print 2>/dev/null || echo "  none produced"
# Echo the identity compiled into this APK so a sideloaded install can be
# matched back to a commit; the app shows the same string on its home screen.
grep -rho 'BUILD_ID = ".*"' "$repo/app/build/generated/source/buildConfig" 2>/dev/null \
  | sort -u | sed 's/^/==> /' || true
