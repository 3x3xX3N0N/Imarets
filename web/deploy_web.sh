#!/bin/bash
# Deploy web/dist to the live site (/opt/litellm-gateway/site, served by Caddy as /srv/site).
# Refuses if check.py fails. Backs up the live tree first; rollback = deploy_web.sh --rollback <ts>.
#   bash web/deploy_web.sh                 # export sources, build, check, deploy
#   bash web/deploy_web.sh --rollback TS
set -euo pipefail
BOX=${BOX:-root@45.77.108.197}
WEB="$(cd "$(dirname "$0")" && pwd)"
LIVE=/opt/litellm-gateway/site
if [ "${1:-}" = "--rollback" ]; then
  TS=${2:?usage: deploy_web.sh --rollback <ts>}
  ssh "$BOX" "test -d $LIVE.bak-web-$TS && rsync -a --delete $LIVE.bak-web-$TS/ $LIVE/ && echo restored $TS"
  exit 0
fi
bash "$WEB/export_sources.sh" >/dev/null
python3 "$WEB/build.py"
python3 "$WEB/check.py"
TS=$(date +%s)
# rsync INTO the existing dir: Caddy's bind mount keeps the directory inode (never mv a new dir over it).
# The old Kotlin build's extra files (site.js, data/, main.css, bloom styles) are left in place but no
# page links them; --delete is not used so a rollback copy is the only thing that changes the tree shape.
ssh "$BOX" "cp -a $LIVE $LIVE.bak-web-$TS"
rsync -a "$WEB/dist/" "$BOX:$LIVE/"
for p in / /index.html /pricing.html /legal/privacy.html /legal/terms.html /styles/app.css /facts.json /api/health; do
  printf "%-20s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' https://verdantbloom.bar$p)"
done
echo "deployed (backup $LIVE.bak-web-$TS; rollback: bash web/deploy_web.sh --rollback $TS)"
