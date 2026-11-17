#!/bin/sh
# Fetches the four glyphs イラロジ from Noto Sans JP (OFL) at wght 800 as a TTF subset for the Home title.
# Google serves TTF to a non-browser user agent; the subset is a few KB.
set -e
cd "$(dirname "$0")/.."
URL=$(curl -s "https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@800&text=%E3%82%A4%E3%83%A9%E3%83%AD%E3%82%B8" | grep -o 'https://[^)]*')
curl -s "$URL" -o app/src/main/res/font/noto_sans_jp_title.ttf
ls -l app/src/main/res/font/noto_sans_jp_title.ttf
