#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${SDK_ROOT}" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is not set." >&2
  exit 1
fi

SDKMANAGER=""
while IFS= read -r candidate; do
  SDKMANAGER="$candidate"
  break
done < <(find "$SDK_ROOT/cmdline-tools" -type f -name sdkmanager 2>/dev/null | sort -r)

if [[ -z "$SDKMANAGER" && -x "$SDK_ROOT/tools/bin/sdkmanager" ]]; then
  SDKMANAGER="$SDK_ROOT/tools/bin/sdkmanager"
fi

if [[ -z "$SDKMANAGER" ]]; then
  echo "sdkmanager was not found under $SDK_ROOT" >&2
  exit 1
fi

# JitPack already provides an Android SDK. Install only the exact packages used
# by this repository so the build does not depend on the image defaults.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;21.4.7075529" \
  "cmake;3.22.1"
