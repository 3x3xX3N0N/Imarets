#!/bin/bash
# Export the live sources the price-tracked site is built from, into web/sources/.
# Run from the repo root on the dev box (needs ssh to the gateway box). Prints no secrets: gateway.json
# and the rate card are public price data; taps.json is reduced to the public fields.
#   bash web/export_sources.sh
set -euo pipefail
BOX=${BOX:-root@45.77.108.197}
OUT="$(cd "$(dirname "$0")" && pwd)/sources"
mkdir -p "$OUT"
ssh "$BOX" 'cat /opt/vbgate/gateway.json' > "$OUT/gateway.json"
ssh "$BOX" 'docker exec -i litellm-gateway-postgres-1 psql -U litellm -d litellm -At' > "$OUT/rate_card.json" <<'SQL'
select json_agg(r order by r.rate_class) from (
  select distinct on (rate_class) rate_class, gpu_ledger, effective_from, card_uusd_per_hour,
         markup_permille, in_uusd_per_mtok, out_uusd_per_mtok, cold_start_ms, idle_cap_ms, k_max
  from ledger.rate_card where effective_from <= now()
  order by rate_class, effective_from desc) r;
SQL
ssh "$BOX" 'python3 - <<PY
import json, re
t = json.load(open("/opt/podctl/taps.json"))
out = {}
for k, v in t.items():
    m = re.search(r"--contextsize (\d+)", v.get("kcpp_args", ""))
    out[k] = {"class": v.get("class"), "weights_path": v.get("weights_path"),
              "weights_size": v.get("weights_size"), "loaded_context": int(m.group(1)) if m else None,
              "queue_depth": v.get("queue_depth"), "max_replicas": v.get("max_replicas")}
print(json.dumps(out, indent=1, sort_keys=True))
PY' > "$OUT/taps.json"
ssh "$BOX" 'curl -s http://172.18.0.1:8090/eta/qwen38-27b' | python3 -c "
import json, sys; d = json.load(sys.stdin)
o = {k: d[k] for k in ('tap', 'boot_median_s', 'boot_p90_s', 'boot_samples')}
o['idle_s'] = int(sys.argv[1]); print(json.dumps(o, indent=1, sort_keys=True))" "$(ssh "$BOX" 'grep -E "^VB_IDLE_S=" /opt/podctl/podctl.env | cut -d= -f2 || true' </dev/null | tr -dc 0-9 | sed 's/^$/600/')" > "$OUT/eta.json"
# Retention facts the API privacy page states (legal/api-privacy.html): pod max life, session length.
# Defaults are the code's own defaults (podctl MAX_LIFE_S 21600, site SESSION_TTL_DAYS 30).
ML=$(ssh "$BOX" 'grep -E "^VB_MAX_LIFE_S=" /opt/podctl/podctl.env | cut -d= -f2 || true' </dev/null | tr -dc 0-9); ML=${ML:-21600}
SD=$(ssh "$BOX" 'grep -E "^SESSION_TTL_DAYS=" /opt/litellm-gateway/site.env | cut -d= -f2 || true' </dev/null | tr -dc 0-9); SD=${SD:-30}
LM=$(ssh "$BOX" 'grep -E "^LOGIN_TTL_MIN=" /opt/litellm-gateway/site.env | cut -d= -f2 || true' </dev/null | tr -dc 0-9); LM=${LM:-15}
printf '{\n "max_life_s": %s,\n "session_ttl_days": %s,\n "login_ttl_min": %s\n}\n' "$ML" "$SD" "$LM" > "$OUT/retention.json"
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/exported_at"
ls -la "$OUT"
