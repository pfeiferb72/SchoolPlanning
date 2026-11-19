#!/bin/sh
set -eu
umask 077
dest=${1:?Zielverzeichnis angeben}
mkdir -p "$dest"
file="$dest/schoolplanning-$(date -u +%Y%m%dT%H%M%SZ).dump"
tmp=$(mktemp "$dest/.backup.XXXXXX")
trap 'rm -f "$tmp"' EXIT
docker compose exec -T db pg_dump -U schoolplanning -d schoolplanning -Fc >"$tmp"
docker compose exec -T db pg_restore -l <"$tmp" >/dev/null
mv "$tmp" "$file"
echo "$file"
