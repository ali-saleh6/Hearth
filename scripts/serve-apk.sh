#!/usr/bin/env bash
# Serve a built APK on the LAN so a TV browser can download it.
#
#   scripts/serve-apk.sh                  # play/qa APK on port 8000
#   PORT=8080 scripts/serve-apk.sh <apk>  # another port or file
#
# The page shows the build identity compiled into the APK, so you can tell
# from the TV whether this is a build you have already installed: sideloaded
# APKs reuse one package name and version and are otherwise indistinguishable.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
apk=${1:-$repo/app/build/outputs/apk/play/qa/app-play-qa.apk}
port=${PORT:-8000}
dir=${SERVE_DIR:-${TMPDIR:-/tmp}/hearth-apk}

[[ -f $apk ]] || { echo "No APK at $apk — build it first (scripts/docker-build.sh)" >&2; exit 1; }
# Absolute, so deriving the variant below works for a relative argument too.
apk="$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"

# The identity is read from BuildConfig but the bytes come from the APK, and
# Gradle regenerates BuildConfig long before it repackages. Serving across that
# window would advertise a build ID the APK does not contain, which is worse
# than no build ID at all.
# Read the identity from the variant that produced THESE bytes. Several
# variants' BuildConfigs coexist, and picking the wrong one advertises another
# build's ID over this APK -- the exact confusion the ID exists to remove.
variant=$(dirname "${apk#"$repo/app/build/outputs/apk/"}")
config=$(find "$repo/app/build/generated/source/buildConfig/$variant" -name BuildConfig.java 2>/dev/null | head -1 || true)
if [[ -n $config && $config -nt $apk ]]; then
  echo "$apk is older than $config: a build is in progress or failed." >&2
  echo "Wait for it to finish, then run this again." >&2
  exit 1
fi

build_id=$(sed -n 's/.*BUILD_ID = "\(.*\)";/\1/p' "$config" 2>/dev/null | head -1)
build_id=${build_id:-unknown}
version=$(sed -n 's/.*VERSION_NAME = "\(.*\)";/\1/p' "$config" 2>/dev/null | head -1)
digest=$(shasum -a 256 "$apk" | cut -d' ' -f1)
size=$(( $(wc -c < "$apk") / 1048576 ))

# A short filename matters: the address is typed with a remote.
mkdir -p "$dir"
cp "$apk" "$dir/h.apk"
cat > "$dir/index.html" <<HTML
<!doctype html>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hearth</title>
<style>
  body{background:#171D46;color:#fff;font:400 2.1rem/1.5 system-ui,sans-serif;
       margin:0;min-height:100vh;display:grid;place-items:center;text-align:center}
  a{display:inline-block;background:#fff;color:#171D46;text-decoration:none;
    padding:1.2rem 3rem;border-radius:.6rem;font-weight:600;margin-top:1.4rem}
  a:focus{outline:6px solid #7aa2ff}
  dl{margin:2rem 0 0;font-size:1.15rem;opacity:.75;display:grid;
     grid-template-columns:auto auto;gap:.35rem 1rem;justify-content:center}
  dt{text-align:right;opacity:.7}
  dd{margin:0;text-align:left;font-family:ui-monospace,monospace}
</style>
<div>
  Hearth $version
  <br><a href="h.apk" download autofocus>Download APK</a>
  <dl>
    <dt>build</dt><dd>$build_id</dd>
    <dt>size</dt><dd>${size} MB</dd>
    <dt>sha256</dt><dd>${digest:0:16}…</dd>
  </dl>
</div>
HTML

address=$(for i in $(ifconfig -l 2>/dev/null); do ipconfig getifaddr "$i" 2>/dev/null && break; done || true)
echo "==> Serving $dir on port $port"
echo "==> build: $build_id"
[[ -n ${address:-} ]] && echo "==> On the TV browser: http://$address:$port"
exec python3 -m http.server "$port" --bind 0.0.0.0 --directory "$dir"
