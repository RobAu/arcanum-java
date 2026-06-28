#!/usr/bin/env bash
# Launch the Arcanum CE Java port. Double-click target for the desktop entry,
# or run from a shell. Optional first arg "world" boots straight into the
# isometric world view (skips the menu).
#
# Game data: uses $ARCANUM_DATA if set, else the bundled sibling build dir, else
# lets the app auto-detect (see GameData.locate). You must own the game.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

# Pick a data dir if we can find one; otherwise the app auto-detects/placeholder.
data="${ARCANUM_DATA:-}"
if [[ -z "$data" ]]; then
    for c in \
        "$here/../../arcanum-ce-c/out/build/linux-x64-debug" \
        "$here/../arcanum-ce-c/out/build/linux-x64-debug"; do
        if [[ -f "$c/tig.dat" ]]; then data="$c"; break; fi
    done
fi

props=()
[[ -n "$data" ]] && props+=("-Darcanum.data=$data")
[[ "${1:-}" == "world" ]] && props+=("-Darcanum.screen=world")

exec ./gradlew --console=plain -q run "${props[@]}"
