#!/usr/bin/env bash
# Renders each diagram in src/ to a PNG at 2x with headless Chrome: ./render.sh [name ...]
# The size comes from the .frame's inline width (default 1200) and height.
set -euo pipefail
cd "$(dirname "$0")"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
for src in ${@:-src/*.html}; do
  [[ "$src" == src/* ]] || src="src/$src.html"
  name=$(basename "$src" .html)
  style=$(grep -o 'class="frame" style="[^"]*"' "$src" | head -1)
  h=$(echo "$style" | grep -o 'height:[0-9]*' | grep -o '[0-9]*')
  w=$(echo "$style" | grep -o 'width:[0-9]*' | grep -o '[0-9]*' || true); w=${w:-1200}
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=2 --window-size="$w","$h" --virtual-time-budget=3000 \
    --screenshot="$PWD/$name.png" "file://$PWD/$src" >/dev/null 2>&1
  echo "$name.png ($w x $h at 2x)"
done
