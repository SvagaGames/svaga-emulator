#!/usr/bin/env bash
# Скачивает готовые libretro-ядра (arm64-v8a) в app/src/main/jniLibs/arm64-v8a.
# Основные ядра обязательны, дополнительные (PS1, N64, NDS и др.) — по возможности: если ядра нет на сервере libretro,
# система просто не будет доступна в этой сборке. Идемпотентно.
set -uo pipefail
cd "$(dirname "$0")"
DEST=app/src/main/jniLibs/arm64-v8a
BASE="https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
REQUIRED="fceumm snes9x genesis_plus_gx gambatte mgba"
# для групп через запятую берётся первое, что скачалось
OPTIONAL="mednafen_pce_fast mednafen_ngp mednafen_wswan mednafen_vb pcsx_rearmed swanstation mupen64plus_next_gles3,mupen64plus_next melondsds,melonds,desmume picodrive ppsspp flycast yabasanshiro opera stella,stella2014 a5200 prosystem handy virtualjaguar freeintv bluemsx o2em vecx pokemini fbneo mame2003_plus prboom"
mkdir -p "$DEST"
command -v unzip >/dev/null || { echo "нужен unzip: apt-get install -y unzip"; exit 1; }

get() {
  local c="$1" out="$DEST/lib${1}_libretro_android.so" tmp
  [ -s "$out" ] && { echo "ok   $c (уже есть)"; return 0; }
  tmp=$(mktemp -d)
  if curl -fsSL --retry 3 -o "$tmp/c.zip" "$BASE/${c}_libretro_android.so.zip" && unzip -oq "$tmp/c.zip" -d "$tmp" && [ -s "$tmp/${c}_libretro_android.so" ]; then
    mv "$tmp/${c}_libretro_android.so" "$out"; echo "ok   $c ($(du -h "$out" | cut -f1))"; rm -rf "$tmp"; return 0
  fi
  rm -rf "$tmp"; return 1
}

fail=0
for c in $REQUIRED; do get "$c" || { echo "FAIL $c (обязательное)"; fail=1; }; done
for group in $OPTIONAL; do
  ok=0
  for c in ${group//,/ }; do get "$c" && { ok=1; break; }; done
  [ "$ok" = 1 ] || echo "skip ${group} (нет на сервере libretro — этой системы в сборке не будет)"
done
[ "$fail" = 0 ] || { echo "Не скачались обязательные ядра"; exit 1; }
ls -l "$DEST"
