#!/usr/bin/env bash
# Rebuilds data/vrs-routes.txt.gz from VRS standing data (CC0, github.com/vradarserver/standing-data).
# Only the routes table is kept. Format: "=XXX" starts a block for callsign prefix XXX,
# then "rest,ORIG-DEST[-...]" per callsign. The first line is "#vrs <commit> <iso date>".
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
git clone -q --depth 1 https://github.com/vradarserver/standing-data "$tmp/vrs"
sha="$(git -C "$tmp/vrs" log -1 --format=%H)"
date="$(git -C "$tmp/vrs" log -1 --format=%cI)"
{
  echo "#vrs $sha $date"
  find "$tmp/vrs/routes/schema-01" -name '*.csv' -exec tail -q -n +2 {} + \
    | awk -F, 'length($1) > 3 && $5 != "" { print substr($1,1,3) "," substr($1,4) "," $5 }' \
    | LC_ALL=C sort -u \
    | awk -F, '$1 != p { print "=" $1; p = $1 } { print $2 "," $3 }'
} | gzip -9n > "$here/data/vrs-routes.txt.gz"
rm -rf "$tmp"
ls -l "$here/data/vrs-routes.txt.gz"
