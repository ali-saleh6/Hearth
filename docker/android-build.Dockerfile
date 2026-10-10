# check=skip=FromPlatformFlagConstDisallowed
# Hearth Android build toolchain.
#
# linux/amd64 is mandatory, not a default: Google ships the Android NDK and
# build-tools for Linux as x86_64 binaries only, with no linux-aarch64 build.
# On Apple Silicon this therefore runs emulated. Build with:
#   docker build --platform=linux/amd64 -f docker/android-build.Dockerfile -t hearth-build .
#
# The image carries the toolchain, not the source. Mount the repository at
# /workspace so Gradle writes app/build straight onto the host, and keep the
# Gradle cache in a named volume. scripts/docker-build.sh does both.
FROM --platform=linux/amd64 eclipse-temurin:17-jdk-jammy@sha256:8d242405506ad1085e39f1ca80ec76f0812f61073efe76129607e39f043ccbb9

# Pinned exactly like scripts/supply-chain-artifacts.tsv: immutable URL plus
# the archive's SHA-256, checked before anything is unpacked or executed.
ARG CMDLINE_TOOLS_VERSION=13114758
ARG CMDLINE_TOOLS_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee

# Versions the project pins: app/build.gradle.kts compileSdk, common-jni
# ndkVersion and its externalNativeBuild cmake version.
ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=27.0.12077973
ARG ANDROID_CMAKE=3.22.1

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    DEBIAN_FRONTEND=noninteractive

# unzip/curl fetch the SDK; python3 runs scripts/verify-release.py; git lets
# Gradle and the gitleaks-style history checks resolve the repo. openjdk-21 is
# required by app/build.gradle.kts testOptions, which runs unit tests on 21
# while production Kotlin/Java stays on 17.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git python3 openjdk-21-jdk-headless \
 && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum --check --strict; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdline-tools.zip

ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# Licenses are accepted for this non-interactive build image only.
RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${ANDROID_NDK}" \
      "cmake;${ANDROID_CMAKE}" \
      "platform-tools" > /dev/null; \
    test -x "${ANDROID_HOME}/ndk/${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"; \
    test -x "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2"

# A dependency in the graph asks for build-tools 35.0.0 beside the pinned 36.0.0.
# Without it AGP auto-downloads it on every run, because --rm discards the
# container filesystem the SDK lives in. Kept in its own layer so adding it does
# not invalidate the NDK layer above and force a multi-GB re-download.
RUN sdkmanager --install "build-tools;35.0.0" > /dev/null

# The repository arrives as a bind mount owned by the host user, so git refuses
# it as "dubious ownership" and app/build.gradle.kts cannot read the revision
# for BUILD_ID. Trust the one path we mount.
RUN git config --global --add safe.directory /workspace

# scripts/verify-release.py resolves the NDK and build-tools through ANDROID_HOME.
WORKDIR /workspace
ENTRYPOINT ["/bin/bash", "-lc"]
CMD ["./gradlew --no-daemon :app:assemblePlayQa"]
