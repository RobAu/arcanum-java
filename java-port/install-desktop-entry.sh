#!/usr/bin/env bash
# Install a clickable desktop launcher for the Arcanum CE Java port:
#   - ~/.local/share/applications/arcanum-ce.desktop  (app menu / search)
#   - ~/Desktop/arcanum-ce.desktop                    (if ~/Desktop exists)
# Re-run after moving the repo. Uninstall: delete those two files.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
play="$here/play.sh"
icon="$here/assets/arcanum-icon.png"
chmod +x "$play"

# Generate the icon locally from the game's own art (a main-menu frame) — it is
# never committed (it's copyrighted game data; see assets/.gitignore). Needs the
# game data; if unavailable we install without a custom icon.
if [[ ! -f "$icon" ]]; then
    data="${ARCANUM_DATA:-}"
    if [[ -z "$data" ]]; then
        for c in "$here/../../arcanum-ce-c/out/build/linux-x64-debug" \
                 "$here/../arcanum-ce-c/out/build/linux-x64-debug"; do
            [[ -f "$c/tig.dat" ]] && { data="$c"; break; }
        done
    fi
    if [[ -n "$data" ]]; then
        mkdir -p "$here/assets"
        echo "rendering icon from game art..."
        ( cd "$here" && ./gradlew --console=plain -q run \
            -Darcanum.data="$data" -Darcanum.screenshot="$icon" ) || true
        if [[ -f "$icon" ]] && command -v convert >/dev/null 2>&1; then
            convert "$icon" -gravity center -crop 600x600+0+0 +repage \
                -resize 256x256 "$icon" 2>/dev/null || true
        fi
    fi
fi

tmp="$(mktemp)"
{
    echo "[Desktop Entry]"
    echo "Type=Application"
    echo "Version=1.0"
    echo "Name=Arcanum CE (Java)"
    echo "Comment=Arcanum: Of Steamworks and Magick Obscura — Community Edition (Java port)"
    echo "Exec=$play"
    [[ -f "$icon" ]] && echo "Icon=$icon"
    echo "Terminal=false"
    echo "Categories=Game;RolePlaying;"
    echo "StartupNotify=true"
} > "$tmp"

apps="$HOME/.local/share/applications"
mkdir -p "$apps"
install -m 644 "$tmp" "$apps/arcanum-ce.desktop"
echo "installed: $apps/arcanum-ce.desktop"

if [[ -d "$HOME/Desktop" ]]; then
    install -m 755 "$tmp" "$HOME/Desktop/arcanum-ce.desktop"
    # GNOME/Nautilus: mark the desktop launcher trusted so it runs on click.
    command -v gio >/dev/null 2>&1 \
        && gio set "$HOME/Desktop/arcanum-ce.desktop" metadata::trusted true 2>/dev/null || true
    echo "installed: $HOME/Desktop/arcanum-ce.desktop"
fi

rm -f "$tmp"
command -v update-desktop-database >/dev/null 2>&1 \
    && update-desktop-database "$apps" 2>/dev/null || true
echo "done. Launch 'Arcanum CE (Java)' from your app menu or the desktop icon."
