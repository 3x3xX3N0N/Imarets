#!/usr/bin/env python3
"""Check web/dist against the live sources. Exit 1 on any problem.

- every level's numbers appear in its table row exactly as the gateway enforces them
- the rate and minimum figures equal facts.json (recomputed from the rate card)
- nothing from the old ten-model site survives (model names, opt-ins, "10 requests a day", "30 seconds")
- CSP-safe: no <script>, no inline style/handlers; every local link and asset resolves
"""
from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path

WEB = Path(__file__).resolve().parent
DIST = WEB / "dist"
problems: list[str] = []


def bad(msg):
    problems.append(msg)


facts = json.loads((DIST / "facts.json").read_text())
g = json.loads((WEB / "sources" / "gateway.json").read_text())

STALE = [r"\bten (taps|pours|bottles|rings)\b", r"\bopt-in", r"\bflagship\b", r"\bDeckard\b", r"\bDark Champion\b",
         r"\bDarkest\b", r"\bRestless Quill\b", r"\bDeep Thinker\b", r"\bDefiant Fable\b", r"\bCold Fusion GAIN\b",
         r"\bCoder 27B\b", r"\b10 requests a day\b", r"\babout 30 s(econds)?\b", r"\bAnother round\b", r"\bhouse tap\b",
         r'"model": "(mini|write|vision|dark|fast|reason|standard|creative|coder|flagship)"']

pages = sorted(p for p in DIST.rglob("*.html"))
for p in pages:
    raw = p.read_text()
    rel = p.relative_to(DIST)
    text = html.unescape(re.sub(r"<[^>]+>", " ", raw))
    if re.search(r"<script\b", raw, re.I):
        bad(f"{rel}: <script>")
    if re.search(r'\sstyle="|\son[a-z]+="', raw, re.I):
        bad(f"{rel}: inline style or event handler")
    if not str(rel).startswith("legal/"):
        for pat in STALE:
            if re.search(pat, text, re.I):
                bad(f"{rel}: stale text /{pat}/")
    for ref in re.findall(r'(?:href|src)="([^"#?]+)', raw):
        if re.match(r"^(https?:|mailto:|/api/)", ref):
            continue
        target = (DIST / ref.lstrip("/")) if ref.startswith("/") else (p.parent / ref)
        if not target.resolve().exists():
            bad(f"{rel}: broken link {ref}")

# level rows: every enforced number, in order, in the row for that level
for page_name in ("index.html", "pricing.html"):
    raw = (DIST / page_name).read_text()
    for lid, lv in g["levels"].items():
        m = re.search(rf'<tr data-level="{lid}">(.*?)</tr>', raw, re.S)
        if not m:
            bad(f"{page_name}: no row for {lid}")
            continue
        cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", m.group(1), re.S)]
        def num(c):
            return int(re.sub(r"\D", "", c) or -1)
        want_price = "free" if lv["tab_usd"] == 0 else f"${lv['tab_usd']} / month"
        checks = [(cells[1], want_price, "price"), (num(cells[2]), lv["daily"], "daily"), (num(cells[3]), lv["rpm"], "rpm"),
                  (num(cells[4]), lv["parallel"], "at once"), (num(cells[5].replace("k", "")) * (1024 if "k" in cells[5] else 1), lv["context"], "context"),
                  (num(cells[6]), lv["max_tokens"], "max reply"),
                  (cells[7] == "yes", lv.get("wakes", True), "wakes")]
        for got, want, what in checks:
            if got != want:
                bad(f"{page_name} {lid} {what}: page {got!r} != gateway {want!r}")

price_text = html.unescape(re.sub(r"<[^>]+>", " ", (DIST / "pricing.html").read_text()))
for label, val in (("in", facts["in_usd_per_mtok"]), ("out", facts["out_usd_per_mtok"])):
    if f"${val:,.2f}".replace(".00", "") not in price_text:
        bad(f"pricing.html: {label} rate {val} not on the page")
for key in ("min_warm_usd", "min_cold_usd", "min_shared_usd"):
    c = facts[key] * 100
    shown = f"{c:.1f}¢" if c < 10 else f"{c:.0f}¢"
    if shown not in price_text:
        bad(f"pricing.html: {key} {shown} not on the page")
for page_name in ("index.html", "pricing.html"):
    t = (DIST / page_name).read_text()
    if f"about {facts['cold_start_median_s']}" not in t:
        bad(f"{page_name}: cold start median {facts['cold_start_median_s']} s missing")

# API privacy notice: present, linked, numbers from the live sources, no unfilled placeholders.
ap_path = DIST / "legal" / "api-privacy.html"
if not ap_path.exists():
    bad("legal/api-privacy.html missing")
else:
    ap_raw = ap_path.read_text()
    ap_text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", ap_raw)))
    eta = json.loads((WEB / "sources" / "eta.json").read_text())
    ret = json.loads((WEB / "sources" / "retention.json").read_text())
    for want, what in ((f"after {round(eta['idle_s'] / 60)} minutes without requests", "pod idle timeout"),
                       (f"at most {round(ret['max_life_s'] / 3600)} hours", "pod max life"),
                       (f"after {ret['session_ttl_days']} days", "session length"),
                       ("We do not store or log what you write", "the core promise")):
        if want not in ap_text:
            bad(f"legal/api-privacy.html: {what} missing ({want!r})")
    if re.search(r"\{[a-z_]+\}", ap_raw):
        bad("legal/api-privacy.html: unfilled {placeholder}")
    sys.path.insert(0, str(WEB))
    import api_privacy
    if api_privacy.ADDRESS_FINAL and "pending]" in ap_text:
        bad("legal/api-privacy.html: ADDRESS_FINAL but the address still says pending")
    if not api_privacy.ADDRESS_FINAL:
        print("note: api-privacy address is a placeholder (PO box pending); DRAFT page")
for p in pages:
    if p.name == "api-privacy.html":
        continue
    link = "api-privacy.html" if p.parent.name == "legal" else "legal/api-privacy.html"
    if f'href="{link}"' not in p.read_text():
        bad(f"{p.relative_to(DIST)}: no link to {link}")

print(f"{len(pages)} pages checked, {len(problems)} problems")
for m in problems:
    print("  -", m)
sys.exit(1 if problems else 0)
