#!/usr/bin/env bash
# 把网页端资源同步进安卓 assets（改完前端后重新执行本脚本）
set -e
SRC="../novel-reader"
DST="app/src/main/assets/www"
rm -rf "$DST"
mkdir -p "$DST"
cp "$SRC/index.html" "$DST/"
cp -r "$SRC/css" "$SRC/js" "$SRC/vendor" "$DST/"
echo "assets synced:"
find "$DST" -maxdepth 2 -type f | sed 's/^/  /'
