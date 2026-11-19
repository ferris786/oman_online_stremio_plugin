#!/usr/bin/env bash
# Generate the profile avatar set with the Codex CLI.
#
# The image tool reaches Codex over MCP from chatgpt.com/backend-api/ps/mcp,
# and that transport drops intermittently ("Transport channel closed" /
# "workspace routing discovery failed") while the base model keeps
# answering. So readiness is never probed with a trivial prompt: it is
# proven by an icon actually landing.
#
# Several passes over the whole list. Anything already on disk is skipped,
# so a pass during an outage costs almost nothing and a later pass collects
# whatever is still missing.
S="$(cd "$(dirname "$0")" && pwd)"
OUT="$S/out"
LOG="$S/progress.log"
MODEL=gpt-6.1-sol
ATTEMPTS=2
PASSES=8

mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" >> "$LOG"; }

gen() {
  local key="$1" subject="$2" bg="$3"
  local dest="$OUT/av_$key.png"
  [ -s "$dest" ] && return 0

  local prompt="Use your image generation tool to create ONE square image and save it in the working directory as av_$key.png.

Image prompt: a premium profile avatar emblem for a television app. Subject: $subject. Rendered as an embossed antique gold emblem with fine Ottoman filigree detail, soft directional lighting and gentle depth, like a cast metal medallion. Background: a solid deep $bg field with a very subtle radial vignette and no pattern. The emblem is centred and occupies roughly 58 percent of the frame width, leaving generous even margins on all four sides so nothing is clipped when the image is cropped to a circle. Square composition. No text, no lettering, no numerals, no watermark, no border, no outer ring, no frame.

Save the file as av_$key.png in the working directory, then state the path."

  for i in $(seq 1 $ATTEMPTS); do
    say "$key attempt $i"
    ( cd "$OUT" && timeout 330 codex exec --cd "$OUT" -s workspace-write \
        --skip-git-repo-check -m "$MODEL" "$prompt" < /dev/null ) >> "$S/codex.log" 2>&1
    if [ -s "$dest" ]; then say "OK   $key"; return 0; fi
    sleep $((20 * i))
  done
  say "FAIL $key after $ATTEMPTS attempts"
  return 1
}

say "=== run starting ==="
for pass in $(seq 1 $PASSES); do
  while IFS='|' read -r key subject bg; do
    [ -z "$key" ] && continue
    [ -s "$OUT/av_$key.png" ] && continue
    gen "$key" "$subject" "$bg" || true
  done <<'LIST'
crescent|an Ottoman crescent moon with a five-pointed star|midnight indigo
tulip|a single stylised Ottoman tulip in bloom|forest olive green
fez|a traditional Turkish fez with a hanging tassel|deep crimson
tea|a tulip-shaped Turkish tea glass on a small saucer, with a wisp of steam|warm chestnut brown
lantern|an ornate hanging Ottoman lantern|deep teal
swords|two crossed Ottoman kilij sabres|slate blue
shield|a round Ottoman shield with a central boss|burgundy red
dagger|an ornate Ottoman jambiya dagger pointing upward|graphite grey
crown|an imperial crown with three points and inset gems|royal plum purple
fortress|a fortress with three crenellated towers and an arched gate|ochre sand brown
ship|a tall sailing galleon under full sail|navy blue
compass|an eight-point compass rose|antique bronze
clapper|a film clapperboard, angled, with its arm raised|charcoal grey
reel|a film reel with five openings around the hub|rust orange
masks|the two theatre masks of comedy and tragedy, side by side|deep violet
vinyl|a vinyl record with concentric grooves and a centre label|near-black warm grey
key|an ornate skeleton key with a decorated bow|forest green
hourglass|an hourglass with sand running through it|wine red
lighthouse|a lighthouse with beams of light cast from its lamp room|deep ocean blue
comet|a comet with a five-pointed head and a streaming tail|midnight blue
owl|a stylised owl seen face on, with large round eyes and ear tufts|moss green
mountains|two mountain peaks with a crescent moon above them|cool slate grey
LIST
  have=$(ls -1 "$OUT"/av_*.png 2>/dev/null | wc -l)
  say "--- pass $pass done: $have of 22 produced ---"
  [ "$have" -ge 22 ] && break
  sleep 240
done
say "=== run finished: $(ls -1 "$OUT"/av_*.png 2>/dev/null | wc -l) of 22 produced ==="
