#!/usr/bin/env python3
"""
build_pages.py - design agent's page generator for verdantbloom.bar (scratch build).

Reads   scratch/content/{copy,models,pricing.public,seo}.json and scratch/content/legal/*.md
Writes  <resources>/index.html, pricing.html, 404.html, legal/*.html, data/*.json

    python site/src/jsMain/resources/_design/build_pages.py            # from scratch/imarets
    python build_pages.py --check                                      # regenerate in memory, diff, exit 1 on drift

Everything the pages say is baked in from the content JSON, so the pages read fully with JavaScript
off. Nothing here is hand-edited afterwards: change content/*.json (content agent) or this file, rerun.

Standard library only. No network. This directory (_design/) is tooling, not part of the site;
the integrator may exclude it (and DOM-CONTRACT.md) from the dist copy.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve()
RES = HERE.parents[1]                      # .../site/src/jsMain/resources
CONTENT = HERE.parents[6] / "content"      # scratch/content

SHELF_ORDER = ["well", "call", "top"]
PASS_NAMES = {"well": "Well", "call": "Call", "top": "Top Shelf"}


def e(text) -> str:
    """HTML-escape text or attribute content."""
    return html.escape(str(text), quote=True)


def load(name: str):
    return json.loads((CONTENT / name).read_text(encoding="utf-8"))


def strip_notes(node):
    """Drop every key starting with '_' (builder notes; content/README.md says never render them)."""
    if isinstance(node, dict):
        return {k: strip_notes(v) for k, v in node.items() if not k.startswith("_")}
    if isinstance(node, list):
        return [strip_notes(v) for v in node]
    return node


# ------------------------------------------------------------------------------------------------
# small formatting helpers
# ------------------------------------------------------------------------------------------------

def asof_time(copy, iso: str) -> str:
    """{asOf}: static fallback string; Kotlin swaps the text for The New Calendar date."""
    return (f'<time class="vb-asof" datetime="{e(iso)}" data-newcal="date">'
            f'{e(copy["_readme"]["asOfFallback"])}</time>')


def with_asof(copy, text: str, iso: str) -> str:
    parts = text.split("{asOf}")
    return asof_time(copy, iso).join(e(p) for p in parts)


def usd(value: float) -> str:
    if float(value).is_integer():
        return f"${int(value)}"
    return f"${value:.2f}"


def cents(value_usd: float) -> str:
    c = round(value_usd * 100, 3)
    text = f"{c:.2f}".rstrip("0").rstrip(".")
    return f"{text} cents"


def data_attrs(prefix: str, mapping: dict) -> str:
    """{'emailRequired': 'x'} -> data-<prefix>-email-required="x" (dataset: <prefix>EmailRequired)."""
    out = []
    for key, value in mapping.items():
        if key.startswith("_") or not isinstance(value, str):
            continue
        kebab = re.sub(r"([A-Z])", lambda m: "-" + m.group(1).lower(), key)
        out.append(f'data-{prefix}-{kebab}="{e(value)}"')
    return " ".join(out)


# ------------------------------------------------------------------------------------------------
# the plate: a real projection of ten Hopf fibers, drawn once as a static figure
# ------------------------------------------------------------------------------------------------

def hopf_plate(models) -> str:
    lat = {"well": -0.35, "call": 0.35, "top": 1.05}           # BloomConfig.shelfLatitudes
    lon_offset = {"well": 0.0, "call": math.pi / 3, "top": math.pi / 2}
    per_shelf = {s: [m for m in models if m["shelf"] == s] for s in SHELF_ORDER}

    # fixed S3 rotation (unit quaternion, scalar first), picked by search so that no fiber passes near
    # the projection pole and the ten rings come out at comparable sizes (largest/smallest = 1.7)
    q = (0.0, 0.838, 0.546, 0.0)
    n = math.sqrt(sum(v * v for v in q))
    q = tuple(v / n for v in q)

    def qmul(a, b):
        return (a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
                a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2],
                a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1],
                a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0])

    # camera: tilt the projected R3 a little, then drop z (orthographic)
    ax, ay = math.radians(62), math.radians(-28)

    def cam(x, y, z):
        y, z = y * math.cos(ax) - z * math.sin(ax), y * math.sin(ax) + z * math.cos(ax)
        x, z = x * math.cos(ay) + z * math.sin(ay), -x * math.sin(ay) + z * math.cos(ay)
        return x, y

    rings = []
    for shelf in SHELF_ORDER:
        group = per_shelf[shelf]
        for i, m in enumerate(group):
            phi = lat[shelf]
            lam = lon_offset[shelf] + 2 * math.pi * i / len(group)
            a, b, c = math.cos(phi) * math.cos(lam), math.cos(phi) * math.sin(lam), math.sin(phi)
            k = 1 / math.sqrt(2 * (1 + c))
            pts = []
            seg = 120
            for j in range(seg):
                t = 2 * math.pi * j / seg
                p = (k * (1 + c) * math.cos(t),
                     k * (a * math.sin(t) - b * math.cos(t)),
                     k * (a * math.cos(t) + b * math.sin(t)),
                     k * (1 + c) * math.sin(t))
                w = qmul(q, p)
                d = 1 - w[3]
                pts.append(cam(w[0] / d, w[1] / d, w[2] / d))
            rings.append((m, pts))

    allx = [p[0] for _, pts in rings for p in pts]
    ally = [p[1] for _, pts in rings for p in pts]
    cx, cy = (min(allx) + max(allx)) / 2, (min(ally) + max(ally)) / 2
    span = max(max(allx) - min(allx), max(ally) - min(ally))
    W, H, pad = 800, 640, 36
    scale = min((W - 2 * pad), (H - 2 * pad)) / span
    sx = (W - 2 * pad) / (max(allx) - min(allx))
    sy = (H - 2 * pad) / (max(ally) - min(ally))
    scale = min(sx, sy)

    paths = []
    for m, pts in rings:
        d = "M" + " L".join(f"{W/2 + (x - cx) * scale:.1f} {H/2 - (y - cy) * scale:.1f}" for x, y in pts) + "Z"
        paths.append(f'<path data-shelf="{e(m["shelf"])}" data-pour-id="{e(m["id"])}" d="{d}"/>')
    frame = f'<path class="vb-plate__frame" d="M0.5 0.5H{W - 0.5}V{H - 0.5}H0.5Z"/>'
    return (f'<svg viewBox="0 0 {W} {H}" role="img" aria-labelledby="plate-title" focusable="false">'
            f'<title id="plate-title">Ten linked rings, one for each pour.</title>'
            f'{frame}{"".join(paths)}</svg>')


# ------------------------------------------------------------------------------------------------
# shared page furniture
# ------------------------------------------------------------------------------------------------

def head(seo, page_key: str, *, root: str, page: str, bundle: bool, styles: list[str], preload_black=True) -> str:
    p = seo["pages"][page_key]
    lines = [
        "<!DOCTYPE html>",
        f'<html lang="{e(seo["site"]["lang"])}" data-theme="absinthe-abyss" data-page="{e(page)}">',
        "<head>",
        '<meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">',
        f"<title>{e(p['title'])}</title>",
        f'<meta name="description" content="{e(p["description"])}">',
        f'<meta name="robots" content="{e(p["robots"])}">',
        f'<link rel="canonical" href="{e(p["canonical"])}">',
        '<meta name="theme-color" content="#020a07">',
        '<meta name="color-scheme" content="dark light">',
        '<meta name="format-detection" content="telephone=no">',
        # site-api sends "Referrer-Policy: no-referrer" on every page. Under that policy a plain
        # <form method="post"> navigation carries "Origin: null", and site-api's cross-site check then
        # refuses the no-JS form posts (403). same-origin keeps the Origin header for our own forms and
        # still sends nothing at all to other sites (the Hugging Face links).
        '<meta name="referrer" content="same-origin">',
    ]
    # og.png does not exist yet (seo.json _notes): no og:image*, twitter:card = summary.
    for key, value in p.get("og", {}).items():
        if key.startswith("og:image"):
            continue
        lines.append(f'<meta property="{e(key)}" content="{e(value)}">')
    tw = p.get("twitter")
    if tw:
        lines.append('<meta name="twitter:card" content="summary">')
        for key in ("twitter:title", "twitter:description"):
            lines.append(f'<meta name="{key}" content="{e(tw[key])}">')
    if preload_black:
        lines.append(f'<link rel="preload" href="{root}fonts/archivo-black-latin-400-normal.woff2" as="font" type="font/woff2" crossorigin>')
    if bundle:
        lines.append(f'<link rel="stylesheet" href="{root}main.css">')
    for s in styles:
        lines.append(f'<link rel="stylesheet" href="{root}styles/{s}.css">')
    lines.append(f'<link rel="stylesheet" href="{root}styles/print.css" media="print">')
    if bundle:
        lines.append(f'<script defer src="{root}site.js"></script>')
    lines.append("</head>")
    return "\n".join(lines)


def target_href(target: str, *, root: str, on_index: bool) -> str:
    idx = "" if on_index else f"{root}index.html"
    table = {
        "waitlist": f"{idx}#list",
        "pourList": f"{idx}#pours",
        "passes": f"{idx}#tab",
        "reducedFee": f"{root}pricing.html#reduced-fee",
        "pricingPage": f"{root}pricing.html",
        "legalAcceptableUse": f"{root}legal/acceptable-use.html",
        "legalPrivacy": f"{root}legal/privacy.html",
        "legalTerms": f"{root}legal/terms.html",
    }
    return table[target]


def top_bar(copy, *, root: str, current: str) -> str:
    on_index = current == "index"
    labels = {l["target"]: l["label"] for l in copy["footer"]["links"]}
    items = []
    for target in ("pourList", "passes", "waitlist", "pricingPage"):
        href = target_href(target, root=root, on_index=on_index)
        cur = ' aria-current="page"' if (target == "pricingPage" and current == "pricing") else ""
        items.append(f'<li><a href="{e(href)}"{cur}>{e(labels[target])}</a></li>')
    home = "#bloom" if on_index else f"{root}index.html"
    return f"""<header class="vb-top">
<div class="vb-wrap vb-top__row">
<a class="vb-wordmark" href="{e(home)}">{e(copy["brand"]["wordmark"])}</a>
<nav class="vb-nav" aria-label="Site">
<ul>
{chr(10).join(items)}
</ul>
</nav>
</div>
</header>"""


def footer(copy, pricing, *, root: str, current: str) -> str:
    on_index = current == "index"
    links = []
    for l in copy["footer"]["links"]:
        href = target_href(l["target"], root=root, on_index=on_index)
        links.append(f'<li><a href="{e(href)}" data-target="{e(l["target"])}">{e(l["label"])}</a></li>')
    credits = "\n".join(f"<li>{e(c)}</li>" for c in copy["footer"]["credits"])
    back = ""
    if current != "index":
        label = copy["pricingPage"]["backToBloom"] if current == "pricing" else copy["legalNav"]["back"]
        back = f'<p><a class="vb-btn" href="{root}index.html">{e(label)}</a></p>'
    return f"""<footer class="vb-foot">
<div class="vb-wrap vb-foot__grid">
<div class="vb-stack">
<p class="vb-foot__age" id="age-notice"><strong>{e(copy["ageNotice"]["sign"])}</strong>{e(copy["ageNotice"]["body"])}</p>
<p>{with_asof(copy, copy["footer"]["line"], pricing["asOf"])}</p>
<p><strong>{e(copy["footer"]["business"])}</strong></p>
{back}
</div>
<nav aria-label="Footer">
<ul class="vb-foot__links">
{chr(10).join(links)}
</ul>
</nav>
<ul class="vb-foot__credits">
{credits}
<li><a href="{root}fonts/OFL-Archivo.txt">Archivo</a>, <a href="{root}fonts/OFL-ArchivoBlack.txt">Archivo Black</a> and <a href="{root}fonts/OFL-JetBrainsMono.txt">JetBrains Mono</a> are served from this site under the SIL Open Font License 1.1.</li>
</ul>
</div>
</footer>"""


# ------------------------------------------------------------------------------------------------
# index.html pieces
# ------------------------------------------------------------------------------------------------

def pour_article(copy, m, ring_id) -> str:
    card = copy["pourList"]["card"]
    badges = copy["pourList"]["badges"]
    on_order = bool(m.get("onOrder"))
    shelf_name = copy["shelves"][m["shelf"]]["header"]

    bl = []
    if on_order:
        bl.append(("onOrder", badges["onOrder"], badges["onOrderHelp"]))
    if m.get("housePour"):
        bl.append(("housePour", badges["housePour"], badges["housePourHelp"]))
    if m.get("guestTap"):
        bl.append(("guestTap", badges["guestTap"], badges["guestTapHelp"]))
    if m.get("thinking"):
        bl.append(("thinks", badges["thinks"], badges["thinksHelp"]))
    badge_html = ""
    if bl:
        lis = "".join(
            f'<li><span class="vb-badge" data-badge="{e(k)}" title="{e(h)}">{e(t)}<span class="vb-vh">: {e(h)}</span></span></li>'
            for k, t, h in bl)
        badge_html = f'<ul class="vb-badges" data-lod-min="mid" aria-label="Marks">{lis}</ul>'

    facts = [
        (card["sizeLabel"], m["size"], "mid"),
        (card["shelfLabel"], shelf_name, "near"),
        (card["baseLabel"], m["base"], "near"),
        (card["makerLabel"], m["maker"], "near"),
    ]
    if m.get("decensor"):
        facts.append((card["labelSays"], m["decensor"], "near"))
    if m.get("pourable") and m.get("coldStartSeconds"):
        facts.append((card["coldPourLabel"], card["coldPourValue"].replace("{s}", str(m["coldStartSeconds"])), "near"))
    facts_html = "".join(
        f'<div data-lod-min="{lod}" class="vb-facts__row"><dt>{e(k)}</dt><dd>{e(v)}</dd></div>' for k, v, lod in facts)

    tags = "".join(f"<li>{e(t)}</li>" for t in m["flavour"])

    if on_order:
        tap = ""
        order_line = ""
        ask = ""
    else:
        tap = (f'<p class="vb-pour__tap" data-lod-min="mid" data-slot="tap">'
               f'<span class="vb-dot" aria-hidden="true"></span>'
               f'<span class="vb-pour__tapword" data-slot="tap-word">{e(badges["unknown"])}</span> '
               f'<span class="vb-pour__taphelp" data-slot="tap-help">{e(badges["unknownHelp"])}</span></p>')
        order_line = f'<p class="vb-pour__order" data-lod-min="near"><code>{e(card["orderExample"].replace("{id}", m["id"]))}</code></p>'
        ask = (f'<a href="#list" data-pass="{e(m["shelf"])}" data-source="pour-{e(m["id"])}">{e(card["askForIt"])}</a>')

    attrs = [
        f'id="pour/{e(m["id"])}"',
        'class="vb-pour"',
        f'data-pour-id="{e(m["id"])}"',
        f'data-shelf="{e(m["shelf"])}"',
        f'data-order="{m["order"]}"',
        f'data-ring-id="{ring_id}"',
        f'data-pourable="{str(bool(m["pourable"])).lower()}"',
        f'data-thinking="{str(bool(m["thinking"])).lower()}"',
        f'data-house-pour="{str(bool(m["housePour"])).lower()}"',
        f'data-guest-tap="{str(bool(m["guestTap"])).lower()}"',
        f'data-on-order="{str(on_order).lower()}"',
    ]
    if not on_order:
        attrs.append('data-tap="unknown"')
    return f"""<article {' '.join(attrs)} aria-labelledby="pour-{e(m['id'])}-name">
<header class="vb-pour__head">
<p class="vb-pour__code"><span class="vb-pour__no">{m['order']:02d}</span> <code>{e(m['id'])}</code></p>
<h4 class="vb-pour__name" id="pour-{e(m['id'])}-name" data-lod-min="mid">{e(m['displayName'])}</h4>
{tap}
</header>
{badge_html}
<dl class="vb-facts">{facts_html}</dl>
<p class="vb-pour__note" data-lod-min="near">{e(m['tastingNote'])}</p>
<ul class="vb-tags" data-lod-min="near" aria-label="Flavour">{tags}</ul>
{order_line}
<p class="vb-pour__links" data-lod-min="near"><a href="{e(m['hfUrl'])}" rel="noopener noreferrer external">{e(card['bottleLink'])}</a>{ask}</p>
</article>"""


def shelves_html(copy, models) -> str:
    out = []
    n = 0
    for shelf in SHELF_ORDER:
        group = [m for m in models["models"] if m["shelf"] == shelf]
        s = copy["shelves"][shelf]
        items = []
        for m in group:
            items.append(f"<li>{pour_article(copy, m, n)}</li>")
            n += 1
        single = " vb-pourlist--single" if len(group) == 1 else ""
        out.append(f"""<section class="vb-shelf" data-shelf="{shelf}" aria-labelledby="shelf-{shelf}-title">
<header class="vb-shelf__head">
<h3 id="shelf-{shelf}-title">{e(s['header'])}</h3>
<p class="vb-shelf__sub">{e(s['sub'])}</p>
<p class="vb-shelf__rates">{e(s['finePrint'])}</p>
</header>
<ol class="vb-pourlist{single}" start="{group[0]['order']}">
{chr(10).join(items)}
</ol>
</section>""")
    s = copy["shelves"]["onOrder"]
    items = "".join(f"<li>{pour_article(copy, m, -1)}</li>" for m in models["onOrder"])
    out.append(f"""<section class="vb-shelf" data-shelf="order" aria-labelledby="shelf-order-title" id="on-order">
<header class="vb-shelf__head">
<h3 id="shelf-order-title">{e(s['header'])}</h3>
<p class="vb-shelf__sub">{e(s['sub'])}</p>
</header>
<ol class="vb-pourlist vb-pourlist--single" start="{models['onOrder'][0]['order']}">
{items}
</ol>
</section>""")
    return "\n".join(out)


def preopen_block(copy) -> str:
    return f"""<div class="vb-preopen" role="note">
<p class="vb-preopen__sign">{e(copy['preOpen']['sign'])}</p>
<p>{e(copy['preOpen']['body'])}</p>
</div>"""


def pass_card(copy, p, *, full: bool, root: str, on_index: bool) -> str:
    pid = p["id"]
    c = copy["passes"]["cards"][pid]
    href = "#list" if on_index else f"{root}index.html?pass={pid}#list"
    extra = ""
    if full:
        rates = "".join(
            f'<span><b>{e(copy["shelves"][s]["header"])}</b> {e(copy["shelves"][s]["finePrint"])}</span><br>'
            for s in p["shelves"])
        extra = f"""<p class="vb-pass__limits">{e(c['limits'])}</p>
<p class="vb-pass__note">{e(c['note'])}</p>
<p class="vb-pass__rates">{rates}</p>"""
        more = ""
    else:
        more = f'<a class="vb-pass__more" href="{root}pricing.html#pass-{pid}">{e(copy["hero"]["ctaTertiary"]["label"])}</a>'
    return f"""<article class="vb-pass" id="pass-{pid}" data-pass-id="{pid}" aria-labelledby="pass-{pid}-name">
<header class="vb-pass__head">
<h3 class="vb-pass__name" id="pass-{pid}-name">{e(c['name'])}</h3>
<p class="vb-pass__price">{e(c['price'])}</p>
</header>
<p class="vb-pass__hours" data-lod-min="mid">{e(c['headline'])}</p>
<p class="vb-pass__on" data-lod-min="mid">{e(c['headlineOn'])}</p>
<p class="vb-pass__tab" data-lod-min="near">{e(c['tab'])}</p>
{extra}
<p class="vb-pass__foot" data-lod-min="near"><a class="vb-btn vb-btn--primary vb-btn--block" href="{e(href)}" data-pass="{pid}" data-source="pass-{pid}">{e(c['button'])}</a>{more}</p>
</article>"""


def warm_cold_html(copy, *, heading_level: int = 3) -> str:
    wc = copy["warmCold"]
    blocks = "".join(
        f'<article class="vb-block"><h{heading_level}>{e(b["head"])}</h{heading_level}><p>{e(b["body"])}</p></article>'
        for b in wc["blocks"])
    return f"""<div class="vb-blocks">{blocks}</div>
<div class="vb-grid vb-grid--2">
<div class="vb-aside"><h{heading_level}>{e(wc['minimumPour']['head'])}</h{heading_level}><p>{e(wc['minimumPour']['body'])}</p></div>
<div class="vb-aside"><p>{e(wc['shared'])}</p></div>
</div>"""


def waitlist_form(copy) -> str:
    w = copy["waitlist"]
    opts = "".join(f'<option value="{e(o["value"])}">{e(o["label"])}</option>' for o in w["passOptions"])
    return f"""<form id="waitlist-form" class="vb-form" method="post" action="/api/subscribe" data-form="waitlist" data-state="idle"
 data-label-submit="{e(w['submit'])}" data-label-submitting="{e(w['submitting'])}" {data_attrs('err', w['errors'])}>
<div class="vb-field">
<label for="wl-email">{e(w['emailLabel'])}</label>
<input class="vb-input" id="wl-email" name="email" type="email" inputmode="email" autocomplete="email" autocapitalize="none" spellcheck="false" maxlength="254" required placeholder="{e(w['emailPlaceholder'])}" aria-describedby="wl-email-error wl-consent">
<p id="wl-email-error" class="vb-field__error" data-error-for="email" hidden></p>
<p class="vb-field__help vb-js-only" id="wl-timing">{e(w['timingNote'])}</p>
</div>
<div class="vb-field">
<label for="wl-pass">{e(w['passLabel'])}</label>
<select class="vb-select" id="wl-pass" name="passInterest" aria-describedby="wl-pass-note wl-pass-error">{opts}</select>
<p id="wl-pass-note" class="vb-note" data-template="{e(w['passPrefilled'])}" hidden></p>
<p id="wl-pass-error" class="vb-field__error" data-error-for="passInterest" hidden></p>
</div>
<div class="vb-hp" aria-hidden="true">
<label for="wl-website">{e(w['honeypotLabel'])}</label>
<input id="wl-website" name="website" type="text" tabindex="-1" autocomplete="off" value="">
</div>
<input type="hidden" id="wl-source" name="source" value="index">
<p id="wl-consent" class="vb-form__consent">{e(w['consent']).replace('See Privacy.', '<a href="legal/privacy.html">See Privacy.</a>')}</p>
<p><button class="vb-btn vb-btn--primary" id="wl-submit" type="submit">{e(w['submit'])}</button></p>
<p id="wl-status" class="vb-form__status" role="status" aria-live="polite" data-state="idle"></p>
</form>
<div id="wl-success" class="vb-form__done" tabindex="-1" hidden>
<h3>{e(w['success']['head'])}</h3>
<p>{e(w['success']['body'])}</p>
</div>"""


def hud_html(copy) -> str:
    h = copy["hud"]
    k = h["knobs"]
    knob_specs = [
        ("rho", k["rho"], 'min="25" max="45" step="0.1" value="28"', "28.0"),
        ("drift", k["drift"], 'min="0.02" max="4" step="0.01" value="1"', "1.00"),
        ("louche", k["louche"], 'min="0" max="1" step="0.01" value="0.35"', "0.35"),
    ]
    knobs = ""
    for kid, kc, attrs, out in knob_specs:
        knobs += f"""<div class="vb-knob" data-knob="{kid}">
<label for="knob-{kid}">{e(kc['label'])}</label>
<div class="vb-knob__well">
<input type="range" id="knob-{kid}" name="{kid}" {attrs} aria-describedby="knob-{kid}-help">
<div class="vb-knob__plate" aria-hidden="true"><div class="vb-knob__dial"></div></div>
</div>
<output id="knob-{kid}-out" for="knob-{kid}">{out}</output>
<p class="vb-help" id="knob-{kid}-help">{e(kc['help'])}</p>
</div>
"""
    s = h["switches"]
    named = ""
    for sid, key in (("daynight", "dayNight"), ("tilt", "tilt"), ("slow", "reducedMotion")):
        named += f"""<label class="vb-switch" data-switch="{key}" for="sw-{sid}">
<input type="checkbox" role="switch" id="sw-{sid}" name="{key}" aria-describedby="sw-{sid}-help">
<span class="vb-switch__slot" aria-hidden="true"></span>
<span class="vb-switch__label">{e(s[key]['label'])}</span>
<span class="vb-help" id="sw-{sid}-help">{e(s[key]['help'])}</span>
</label>
"""
    blank = ""
    pattern = h["unlabeledSwitches"]["ariaLabelPattern"]
    for n in range(1, 5):
        blank += f"""<label class="vb-switch vb-switch--blank" data-switch="u{n}" for="sw-u{n}">
<input type="checkbox" role="switch" id="sw-u{n}" name="u{n}" aria-label="{e(pattern.replace('{n}', str(n)))}">
<span class="vb-switch__slot" aria-hidden="true"></span>
</label>
"""
    b = h["bell"]
    return f"""<aside id="hud" class="vb-hud vb-js-only" aria-label="{e(h['title'])}" data-open="false">
<div id="hud-panel" class="vb-hud__panel" hidden>
<h2 class="vb-hud__title" id="hud-title">{e(h['title'])}</h2>
<fieldset class="vb-hud__group">
<legend class="vb-vh">Knobs</legend>
<div class="vb-knobs">
{knobs}</div>
</fieldset>
<div class="vb-hud__group">
<button type="button" id="bell" class="vb-bell" data-state="idle" aria-describedby="bell-help" data-msg-ringing="{e(b['ringing'])}" data-msg-answered="{e(b['answered'])}" data-msg-no-answer="{e(b['noAnswer'])}">{e(b['label'])}</button>
<p id="bell-status" class="vb-bell__status" role="status" aria-live="polite"></p>
<p class="vb-help" id="bell-help">{e(b['help'])}</p>
</div>
<fieldset class="vb-hud__group">
<legend class="vb-vh">Switches</legend>
<div class="vb-switches">
{named}</div>
</fieldset>
<fieldset class="vb-hud__group">
<legend class="vb-vh">More switches</legend>
<div class="vb-switches vb-switches--blank">
{blank}</div>
</fieldset>
<div class="vb-hud__group vb-hud__reset">
<button type="button" id="hud-reset" class="vb-btn vb-btn--quiet">{e(h['reset'])}</button>
<p class="vb-help">{e(h['resetHelp'])}</p>
</div>
<p class="vb-hud__note">{e(h['localNote'])}</p>
</div>
<button type="button" id="hud-toggle" class="vb-hud__toggle" aria-expanded="false" aria-controls="hud-panel"><span data-when="closed">{e(h['open'])}</span><span data-when="open">{e(h['close'])}</span></button>
</aside>"""


def tour_html(copy, models) -> str:
    t = copy["tour"]
    stops = [("bloom", "#bloom", t["stops"]["bloom"], "")]
    for i, m in enumerate(models["models"]):
        stops.append((f"pour/{m['id']}", f"#pour/{m['id']}", t["stops"]["pour"].replace("{id}", m["id"]), str(i)))
    for m in models["onOrder"]:
        stops.append((f"pour/{m['id']}", f"#pour/{m['id']}", t["stops"]["onOrder"], ""))
    stops += [("tab", "#tab", t["stops"]["tab"], ""), ("list", "#list", t["stops"]["list"], ""), ("sign", "#sign", t["stops"]["sign"], "")]
    lis = "".join(
        f'<li data-stop="{e(s)}" data-ring-id="{e(r)}"><a href="{e(h)}">{e(l)}</a></li>' if r != "" else
        f'<li data-stop="{e(s)}"><a href="{e(h)}">{e(l)}</a></li>'
        for s, h, l, r in stops)
    pos = t["position"].replace("{i}", "1").replace("{count}", str(len(stops)))
    return f"""<nav id="tour" class="vb-tour vb-js-only" aria-label="{e(t['barLabel'])}" data-index="0" data-count="{len(stops)}" data-msg-end="{e(t['end'])}" data-msg-start="{e(t['start'])}">
<button type="button" id="tour-back" aria-keyshortcuts="Escape" disabled>{e(t['back'])}<kbd>{e(t['backHelp'])}</kbd></button>
<button type="button" id="tour-prev" disabled>{e(t['prev'])}</button>
<p class="vb-tour__where" aria-live="polite"><span id="tour-stop" class="vb-tour__stop">{e(t['overview'])}</span><span id="tour-pos" class="vb-tour__pos" data-template="{e(t['position'])}">{e(pos)}</span></p>
<button type="button" id="tour-next">{e(t['next'])}</button>
<ol id="tour-stops" class="vb-tour__stops">{lis}</ol>
</nav>"""


def plate_caption(caption: str) -> str:
    """The static plate draws ten rings and no visitor ring, so its caption drops the visitor sentence."""
    tail = " The eleventh ring is you."
    if not caption.endswith(tail):
        raise ValueError("copy.bloom.caption changed; re-check the static plate caption")
    return caption[: -len(tail)]


def build_index(copy, models, pricing, seo) -> str:
    root = ""
    hero, bloom, clock, sign = copy["hero"], copy["bloom"], copy["clock"], copy["statusSign"]
    cta = "".join(
        f'<a class="vb-btn{" vb-btn--primary" if key == "ctaPrimary" else ""}" href="{e(target_href(hero[key]["target"], root=root, on_index=True))}" data-target="{e(hero[key]["target"])}">{e(hero[key]["label"])}</a>'
        for key in ("ctaPrimary", "ctaSecondary", "ctaTertiary"))

    sign_attrs = []
    for state, v in sign["states"].items():
        sign_attrs.append(f'data-sign-{state}="{e(v["sign"])}"')
        sign_attrs.append(f'data-plain-{state}="{e(v["plain"])}"')
    sign_attrs.append(f'data-sign-open-one="{e(sign["states"]["open"]["signOne"])}"')
    sign_attrs.append(f'data-sign-loading="{e(sign["loading"])}"')
    sign_attrs.append(f'data-sign-stale="{e(sign["stale"])}"')
    sign_attrs.append(f'data-total="{len(models["models"])}"')

    fb = bloom["rendererFallback"]
    passes = "\n".join(pass_card(copy, p, full=False, root=root, on_index=True) for p in pricing["passes"])
    badges = copy["pourList"]["badges"]
    tap_words = " ".join(
        f'data-tap-{k}="{e(badges[k])}" data-tap-{k}-help="{e(badges[k + "Help"])}"' for k in ("warm", "cold", "parked", "unknown"))

    return f"""{head(seo, 'index', root=root, page='index', bundle=True, styles=['tokens', 'fonts', 'type', 'layout', 'components', 'bloom'])}
<body>
<a class="vb-skip" href="#doc">{e(bloom['skipLink'])}</a>
{top_bar(copy, root=root, current='index')}
<main id="main">

<section id="bloom" class="vb-hero" aria-labelledby="hero-title" data-node="bloom">
<div class="vb-hero__copy">
<p class="vb-label vb-hero__eyebrow">{e(hero['eyebrow'])}</p>
<h1 id="hero-title">{e(hero['headline'])}</h1>
<p class="vb-hero__positioning">{e(hero['positioning'])}</p>
<p class="vb-lede">{e(hero['sub'])}</p>
<p>{e(hero['body'])}</p>
<p class="vb-hero__status">{e(hero['status'])}</p>
<div class="vb-cta">{cta}</div>
<p class="vb-hero__age">{e(hero['ageLine'])}</p>
<a class="vb-signmini vb-js-only" href="#sign"><span class="vb-dot" aria-hidden="true"></span><span data-bind="sign.line">{e(sign['loading'])}</span></a>
</div>
<div class="vb-hero__stagecol">
<div id="stage" class="vb-stage" data-renderer="none">
<div id="bloom-host" class="vb-stage__host vb-js-only" role="img" aria-label="{e(bloom['canvasAriaLabel'])}"></div>
<figure id="bloom-plate" class="vb-plate">
{hopf_plate(models['models'])}
<figcaption><b>FIG. 1</b> {e(plate_caption(bloom['caption']))}</figcaption>
</figure>
<div id="bloom-cards" class="vb-stage__cards vb-js-only"><span class="vb-ringtag" data-ring-id="10" data-ring="visitor" hidden>{e(bloom['visitorRingLabel'])}</span></div>
<div id="clock" class="vb-clock vb-stage__clock" data-clock-state="now">
<dl>
<div class="vb-clock__row"><dt>{e(clock['newCalLabel'])}</dt><dd><time id="clock-newcal" data-bind="clock.newcal"></time></dd></div>
<div class="vb-clock__row"><dt>{e(clock['unixLabel'])}</dt><dd><span id="clock-unix" data-bind="clock.unix"></span></dd></div>
</dl>
<p id="clock-note" class="vb-clock__note" data-origin-sign="{e(clock['originSign'])}" data-origin-note="{e(clock['originNote'])}" data-pinned-note="{e(clock['pinnedNote'])}" hidden></p>
<button type="button" id="clock-now" class="vb-btn vb-btn--quiet" hidden>{e(clock['backToNow'])}</button>
</div>
<p id="bloom-notice" class="vb-stage__notice" role="status" data-msg-webgpu-to-webgl="{e(fb['webgpuToWebgl'])}" data-msg-webgl-to-2d="{e(fb['webglTo2d'])}" data-msg-all-failed="{e(fb['allFailed'])}" hidden></p>
</div>
</div>
<div class="vb-hero__notes">
<p class="vb-js-only">{e(bloom['caption'])}</p>
<p class="vb-js-only">{e(bloom['clock'])}</p>
<p class="vb-js-only">{e(bloom['neverStill'])}</p>
<p class="vb-nojs-only">{e(bloom['noscript'])}</p>
</div>
</section>

<div id="doc">

<section id="sign" class="vb-section vb-sign" aria-labelledby="sign-title" data-node="sign">
<div class="vb-wrap">
<div class="vb-section__head">
<h2 id="sign-title">{e(sign['title'])}</h2>
<p>{e(copy['pourList']['liveHint'])}</p>
</div>
<div id="sign-board" class="vb-sign__board" {' '.join(sign_attrs)}>
<p id="sign-line" class="vb-sign__line" role="status" aria-live="polite" data-bind="sign.line">{e(copy['preOpen']['sign'])}</p>
<p id="sign-plain" class="vb-sign__plain" data-bind="sign.plain">{e(sign['noscript'])}</p>
<p class="vb-sign__meta vb-js-only"><span>{e(sign['asOfLabel'])} <time id="sign-asof" data-bind="sign.asOf"></time></span><span id="sign-members" data-bind="sign.members" data-template="{e(sign['members'])}"></span></p>
</div>
</div>
</section>

<section id="pours" class="vb-section" aria-labelledby="pours-title" data-node="pours" {tap_words}>
<div class="vb-wrap">
<div class="vb-section__head">
<h2 id="pours-title">{e(copy['pourList']['title'])}</h2>
<p>{e(copy['pourList']['intro'])}</p>
<p class="vb-fine">{e(copy['pourList']['orderHint'])}</p>
</div>
{shelves_html(copy, models)}
<div class="vb-stack vb-fine" id="rates-small-print">
{''.join(f'<p>{e(t)}</p>' for t in copy['shelves']['ratesSmallPrint'])}
</div>
</div>
</section>

<section id="tab" class="vb-section vb-section--tint-2" aria-labelledby="tab-title" data-node="tab">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="tab-title">{e(copy['passes']['title'])}</h2>
<p>{e(copy['passes']['intro'])}</p>
</div>
{preopen_block(copy)}
<div class="vb-passes">
{passes}
</div>
<p class="vb-fine">{e(copy['passes']['preOpen'])}</p>
<div class="vb-callout">
<h3>{e(copy['passes']['hasABottom']['title'])}</h3>
<p>{e(copy['passes']['hasABottom']['body'])}</p>
</div>
<p><a class="vb-btn" href="pricing.html">{e(copy['hero']['ctaTertiary']['label'])}</a></p>
</div>
</section>

<section id="warm-cold" class="vb-section" aria-labelledby="warm-cold-title" data-node="warm-cold">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="warm-cold-title">{e(copy['warmCold']['title'])}</h2>
</div>
{warm_cold_html(copy)}
</div>
</section>

<section id="list" class="vb-section vb-section--tint-2" aria-labelledby="list-title" data-node="list">
<div class="vb-wrap">
<div class="vb-section__head">
<h2 id="list-title">{e(copy['waitlist']['title'])}</h2>
<p>{e(copy['waitlist']['headline'])}</p>
<p class="vb-fine">{e(copy['waitlist']['body'])}</p>
</div>
{waitlist_form(copy)}
</div>
</section>

</div>
</main>
{footer(copy, pricing, root=root, current='index')}
{hud_html(copy)}
{tour_html(copy, models)}
</body>
</html>
"""


# ------------------------------------------------------------------------------------------------
# pricing.html
# ------------------------------------------------------------------------------------------------

def rate_table(copy, pricing) -> str:
    head_cells = "".join(f'<th scope="col">{e(h)}</th>' for h in copy["pricingPage"]["rateTableHead"])
    rows = ""
    for s in pricing["shelves"]:
        rows += (f'<tr data-shelf="{e(s["id"])}"><th scope="row">{e(copy["shelves"][s["id"]]["header"])}</th>'
                 f'<td class="vb-num">{usd(s["inputPerMtok"])}</td><td class="vb-num">{usd(s["outputPerMtok"])}</td>'
                 f'<td class="vb-num">{cents(s["minPour"]["warm"])}</td><td class="vb-num">{cents(s["minPour"]["cold"])}</td></tr>')
    return f"""<div class="vb-board-scroll" tabindex="0" role="region" aria-labelledby="rates-caption">
<table class="vb-board">
<caption id="rates-caption">{e(copy['pourList']['title'])}</caption>
<thead><tr>{head_cells}</tr></thead>
<tbody>{rows}</tbody>
</table>
</div>"""


def shelf_lists(copy) -> str:
    out = ""
    for key in ("well", "call", "top", "onOrder"):
        s = copy["shelves"][key]
        shelf = "order" if key == "onOrder" else key
        fine = f'<p class="vb-shelf__rates">{e(s["finePrint"])}</p>' if "finePrint" in s else ""
        out += f"""<section class="vb-shelf" data-shelf="{shelf}" aria-labelledby="pshelf-{shelf}">
<header class="vb-shelf__head">
<h3 id="pshelf-{shelf}">{e(s['header'])}</h3>
<p class="vb-shelf__sub">{e(s['sub'])}</p>
<p class="vb-mono">{e(s['list'])}</p>
{fine}
</header>
</section>
"""
    return out


def last_call_table(copy) -> str:
    lc = copy["lastCall"]
    head_cells = "".join(f'<th scope="col">{e(h)}</th>' for h in lc["tableHead"])
    rows = "".join(f'<tr><th scope="row" class="vb-num">{e(r[0])}</th><td>{e(r[1])}</td></tr>' for r in lc["rows"])
    return f"""<div class="vb-board-scroll" tabindex="0" role="region" aria-labelledby="last-call-caption">
<table class="vb-board vb-board--wide">
<caption id="last-call-caption">{e(lc['title'])}</caption>
<thead><tr>{head_cells}</tr></thead>
<tbody>{rows}</tbody>
</table>
</div>"""


def reduced_fee_table(copy) -> str:
    rf = copy["reducedFee"]
    head_cells = "".join(
        f'<th scope="col">{e(h)}</th>' if h else '<td></td>' for h in rf["tableHead"])
    rows = ""
    for r in rf["rows"]:
        cells = ""
        for v in r[1:]:
            if v == "-":
                cells += '<td data-empty="true"><span aria-hidden="true">-</span><span class="vb-vh">not offered</span></td>'
            else:
                cells += f'<td class="vb-price">{e(v)}</td>'
        rows += f'<tr><th scope="row">{e(r[0])}</th>{cells}</tr>'
    return f"""<div class="vb-board-scroll" tabindex="0" role="region" aria-labelledby="rf-caption">
<table class="vb-board">
<caption id="rf-caption">{e(rf['sub'])}</caption>
<thead><tr>{head_cells}</tr></thead>
<tbody>{rows}</tbody>
</table>
</div>"""


def reduced_fee_form(copy, pricing) -> str:
    rf = copy["reducedFee"]
    f = rf["form"]
    fields = {x["id"]: x for x in pricing["reducedFee"]["formFields"]}
    fc = f["fields"]

    def flag(fid):
        return f'<span class="vb-field__flag">{e(f["required"] if fields[fid]["required"] else f["optional"])}</span>'

    def req(fid):
        return " required" if fields[fid]["required"] else ""

    def options(fid):
        return '<option value="">Pick one</option>' + "".join(
            f'<option value="{e(o)}">{e(o)}</option>' for o in fields[fid]["options"])

    max_len = fields["circumstances"]["maxLength"]
    counter = fc["circumstances"]["counter"]
    return f"""<form id="reduced-fee-form" class="vb-form" method="post" action="/api/reduced-fee" data-form="reduced-fee" data-state="idle"
 data-label-submit="{e(f['submit'])}" data-label-submitting="{e(f['submitting'])}" {data_attrs('err', rf['errors'])}>
<fieldset>
<legend>{e(f['legend'])}</legend>
<div class="vb-field">
<label for="rf-contact">{e(fields['contact']['label'])}{flag('contact')}</label>
<input class="vb-input" id="rf-contact" name="contact" type="text" autocomplete="email" autocapitalize="none" spellcheck="false" minlength="3" maxlength="200"{req('contact')} aria-describedby="rf-contact-help rf-contact-error">
<p id="rf-contact-help" class="vb-field__help">{e(fc['contact']['help'])}</p>
<p id="rf-contact-error" class="vb-field__error" data-error-for="contact" hidden></p>
</div>
<div class="vb-field">
<label for="rf-pass">{e(fields['pass']['label'])}{flag('pass')}</label>
<select class="vb-select" id="rf-pass" name="pass"{req('pass')} aria-describedby="rf-pass-error">{options('pass')}</select>
<p id="rf-pass-error" class="vb-field__error" data-error-for="pass" hidden></p>
</div>
<div class="vb-field">
<label for="rf-rate">{e(fields['rate']['label'])}{flag('rate')}</label>
<select class="vb-select" id="rf-rate" name="rate"{req('rate')} aria-describedby="rf-rate-help rf-rate-error">{options('rate')}</select>
<p id="rf-rate-help" class="vb-field__help">{e(fc['rate']['help'])}</p>
<p id="rf-rate-error" class="vb-field__error" data-error-for="rate" hidden></p>
</div>
<div class="vb-field">
<label for="rf-circumstances">{e(fields['circumstances']['label'])}{flag('circumstances')}</label>
<textarea class="vb-textarea" id="rf-circumstances" name="circumstances" rows="5" minlength="3" maxlength="{max_len}"{req('circumstances')} aria-describedby="rf-circumstances-help rf-circumstances-error"></textarea>
<p id="rf-circumstances-count" class="vb-field__count vb-js-only" data-template="{e(counter)}" aria-hidden="true">{e(counter.replace('{n}', '0'))}</p>
<p id="rf-circumstances-help" class="vb-field__help">{e(fc['circumstances']['help'])}</p>
<p id="rf-circumstances-error" class="vb-field__error" data-error-for="circumstances" hidden></p>
</div>
<div class="vb-field">
<label for="rf-country">{e(fields['country']['label'])}{flag('country')}</label>
<input class="vb-input" id="rf-country" name="country" type="text" autocomplete="country-name" maxlength="60"{req('country')} aria-describedby="rf-country-error">
<p id="rf-country-error" class="vb-field__error" data-error-for="country" hidden></p>
</div>
<div class="vb-field">
<label class="vb-check" for="rf-attest"><input id="rf-attest" name="attest" type="checkbox" value="on"{req('attest')} aria-describedby="rf-attest-error"><span>{e(fields['attest']['label'])}{flag('attest')}</span></label>
<p id="rf-attest-error" class="vb-field__error" data-error-for="attest" hidden></p>
</div>
<div class="vb-hp" aria-hidden="true">
<label for="rf-website">{e(f['honeypotLabel'])}</label>
<input id="rf-website" name="website" type="text" tabindex="-1" autocomplete="off" value="">
</div>
</fieldset>
<p id="rf-consent" class="vb-form__consent">{e(f['consent']).replace('See Privacy.', '<a href="legal/privacy.html">See Privacy.</a>')}</p>
<p><button class="vb-btn vb-btn--primary" id="rf-submit" type="submit">{e(f['submit'])}</button></p>
<p id="rf-status" class="vb-form__status" role="status" aria-live="polite" data-state="idle"></p>
</form>
<div id="rf-success" class="vb-form__done" tabindex="-1" hidden>
<h3>{e(rf['success']['head'])}</h3>
<p>{e(rf['success']['body'])}</p>
</div>"""


def build_pricing(copy, models, pricing, seo) -> str:
    root = ""
    pp = copy["pricingPage"]
    titles = {
        "preOpen": (copy["preOpen"]["sign"], "pre-open"),
        "passes": (copy["passes"]["title"], "passes"),
        "shelves": (copy["pourList"]["title"], "shelves"),
        "warmCold": (copy["warmCold"]["title"], "warm-cold"),
        "anotherRound": (copy["anotherRound"]["title"], "another-round"),
        "lastCall": (copy["lastCall"]["title"], "last-call"),
        "reducedFee": ("REDUCED FEE", "reduced-fee"),
        "patron": (copy["patron"]["title"], "patron"),
        "disclaimers": (pp["disclaimersTitle"], "disclaimers"),
        "faq": (copy["faq"]["title"], "faq"),
    }
    reduced_label = next(l["label"] for l in copy["footer"]["links"] if l["target"] == "reducedFee")
    titles["reducedFee"] = (reduced_label, "reduced-fee")
    index_items = "".join(
        f'<li><a href="#{anchor}">{e(title)}</a></li>' for key in pp["sections"] for title, anchor in [titles[key]])

    passes = "\n".join(pass_card(copy, p, full=True, root=root, on_index=False) for p in pricing["passes"])
    fine = "".join(f"<li><span>{e(t)}</span></li>" for t in copy["passes"]["finePrint"])
    ar = copy["anotherRound"]
    rounds = "".join(f"<li>{usd(t['price'])}</li>" for t in pricing["topUps"])
    lc = copy["lastCall"]
    rules = "".join(f"<li><span>{e(t)}</span></li>" for t in lc["rules"])
    rf = copy["reducedFee"]
    pat = copy["patron"]
    disclaimers = "".join(f"<li><span>{e(t)}</span></li>" for t in pricing["disclaimers"])
    aup = "".join(f"<li>{e(t)}</li>" for t in pricing["acceptableUse"])
    faq = "".join(
        f'<div class="vb-faq__item" id="faq-{e(i["id"])}"><dt>{e(i["q"])}</dt><dd>{e(i["a"])}</dd></div>'
        for i in copy["faq"]["items"])

    return f"""{head(seo, 'pricing', root=root, page='pricing', bundle=True, styles=['tokens', 'fonts', 'type', 'layout', 'components'])}
<body>
{top_bar(copy, root=root, current='pricing')}
<main id="main">

<div class="vb-wrap vb-pagehead">
<h1>{e(pp['title'])}</h1>
<p class="vb-lede">{with_asof(copy, pp['intro'], pricing['asOf'])}</p>
<nav aria-label="On this page">
<ol class="vb-index">{index_items}</ol>
</nav>
</div>

<section id="pre-open" class="vb-section" aria-label="{e(copy['preOpen']['sign'])}">
<div class="vb-wrap">
{preopen_block(copy)}
</div>
</section>

<section id="passes" class="vb-section vb-section--tint-2" aria-labelledby="passes-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="passes-title">{e(copy['passes']['title'])}</h2>
<p>{e(copy['passes']['intro'])}</p>
<p class="vb-fine">{e(copy['passes']['preOpen'])}</p>
</div>
<div class="vb-passes">
{passes}
</div>
<div>
<h3 id="fine-print">{e(copy['passes']['finePrintTitle'])}</h3>
<ol class="vb-fineprint">{fine}</ol>
</div>
<div class="vb-callout">
<h3>{e(copy['passes']['hasABottom']['title'])}</h3>
<p>{e(copy['passes']['hasABottom']['body'])}</p>
</div>
</div>
</section>

<section id="shelves" class="vb-section" aria-labelledby="shelves-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="shelves-title">{e(copy['pourList']['title'])}</h2>
<p>{e(copy['pourList']['intro'])}</p>
<p class="vb-fine">{e(copy['pourList']['orderHint'])}</p>
</div>
{rate_table(copy, pricing)}
<div>
{shelf_lists(copy)}
</div>
<div class="vb-stack">
{''.join(f'<p>{e(t)}</p>' for t in copy['shelves']['ratesSmallPrint'])}
</div>
<p><a class="vb-btn" href="index.html#pours">{e(copy['hero']['ctaSecondary']['label'])}</a></p>
</div>
</section>

<section id="warm-cold" class="vb-section vb-section--tint-2" aria-labelledby="warm-cold-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="warm-cold-title">{e(copy['warmCold']['title'])}</h2>
</div>
{warm_cold_html(copy)}
</div>
</section>

<section id="another-round" class="vb-section" aria-labelledby="another-round-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="another-round-title">{e(ar['title'])}</h2>
<p>{e(ar['headline'])}</p>
<p class="vb-fine">{e(ar['body'])}</p>
</div>
<ul class="vb-rounds" aria-label="{e(ar['title'])}">{rounds}</ul>
<p class="vb-note">{e(ar['preOpen'])}</p>
</div>
</section>

<section id="last-call" class="vb-section vb-section--tint-2" aria-labelledby="last-call-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="last-call-title">{e(lc['title'])}</h2>
<p>{e(lc['headline'])}</p>
</div>
{last_call_table(copy)}
<p class="vb-num vb-js-only" id="members-counter" data-bind="sign.members" data-template="{e(lc['counter'])}" hidden></p>
<ol class="vb-fineprint">{rules}</ol>
</div>
</section>

<section id="reduced-fee" class="vb-section" aria-labelledby="reduced-fee-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="reduced-fee-title" class="vb-h2--long">{e(rf['title'])}</h2>
<p>{e(rf['headline'])}</p>
<p class="vb-fine">{e(rf['sub'])}</p>
</div>
{reduced_fee_table(copy)}
<p class="vb-fine">{e(rf['tableNote'])}</p>
<div class="vb-stack">
{''.join(f'<p>{e(t)}</p>' for t in rf['how'])}
<p>{e(rf['whoPays'])}</p>
<p>{e(rf['cap'])}</p>
</div>
<p class="vb-notice" id="rf-queue-notice">{e(rf['queueNotice'])}</p>
{reduced_fee_form(copy, pricing)}
</div>
</section>

<section id="patron" class="vb-section vb-section--tint-2" aria-labelledby="patron-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="patron-title">{e(pat['title'])}</h2>
<p>{e(pat['options'])}</p>
</div>
<div class="vb-grid vb-grid--2">
{''.join(f'<p class="vb-lede">{e(t)}</p>' for t in pat['pitch'])}
</div>
<p class="vb-note">{e(pat['unit'])}</p>
<div class="vb-stack">
<p>{e(pat['mechanism'])}</p>
<p>{e(pat['refund'])}</p>
<p><strong>{e(copy['footer']['business'])}</strong></p>
</div>
<p class="vb-note">{e(pat['preOpen'])}</p>
</div>
</section>

<section id="disclaimers" class="vb-section" aria-labelledby="disclaimers-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="disclaimers-title">{e(pp['disclaimersTitle'])}</h2>
</div>
<ol class="vb-fineprint">{disclaimers}</ol>
<div class="vb-aside" id="acceptable-use-summary">
<h3>{e(copy['legalNav']['acceptableUse'])}</h3>
<ul class="vb-stack">{aup}</ul>
<p><a href="legal/acceptable-use.html">{e(copy['legalNav']['acceptableUse'])}</a></p>
</div>
</div>
</section>

<section id="faq" class="vb-section vb-section--tint-2" aria-labelledby="faq-title">
<div class="vb-wrap">
<div class="vb-section__head">
<h2 id="faq-title">{e(copy['faq']['title'])}</h2>
</div>
<dl class="vb-faq">{faq}</dl>
</div>
</section>

</main>
{footer(copy, pricing, root=root, current='pricing')}
</body>
</html>
"""


# ------------------------------------------------------------------------------------------------
# legal pages: a small, strict Markdown subset (headings, paragraphs, lists, one blockquote,
# bold, inline code, links). Raises on anything it does not know, rather than guessing.
# ------------------------------------------------------------------------------------------------

LINK_MAP = {"acceptable-use.md": "acceptable-use.html", "privacy.md": "privacy.html", "terms-stub.md": "terms.html"}


def md_inline(text: str) -> str:
    out = e(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)

    def link(m):
        label, href = m.group(1), html.unescape(m.group(2))
        href = LINK_MAP.get(href, href)
        if not re.fullmatch(r"[a-z0-9./#-]+", href):
            raise ValueError(f"unexpected link target in legal markdown: {href}")
        return f'<a href="{e(href)}">{label}</a>'

    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, out)


def md_to_html(md: str) -> str:
    md = re.sub(r"\A\s*<!--.*?-->\s*", "", md, flags=re.S)       # builder note: never rendered
    if "<" in md:
        raise ValueError("legal markdown contains raw HTML; refusing to pass it through")
    lines = md.replace("\r\n", "\n").split("\n")
    out, para, items, quote = [], [], [], []

    def flush():
        nonlocal para, items, quote
        if para:
            out.append(f"<p>{md_inline(' '.join(para))}</p>")
            para = []
        if items:
            out.append("<ul>" + "".join(f"<li>{md_inline(i)}</li>" for i in items) + "</ul>")
            items = []
        if quote:
            text = " ".join(quote)
            m = re.match(r"\*\*(.+?)\*\*\s*(.*)", text)
            if m:
                out.append(f'<aside class="vb-banner" role="note"><strong>{e(m.group(1))}</strong><p>{md_inline(m.group(2))}</p></aside>')
            else:
                out.append(f'<aside class="vb-banner" role="note"><p>{md_inline(text)}</p></aside>')
            quote = []

    for raw in lines:
        line = raw.rstrip()
        if not line.strip():
            flush()
        elif line.startswith("# "):
            flush(); out.append(f"<h1>{md_inline(line[2:])}</h1>")
        elif line.startswith("## "):
            flush(); out.append(f"<h2>{md_inline(line[3:])}</h2>")
        elif line.startswith("> "):
            if para or items:
                flush()
            quote.append(line[2:].strip())
        elif line.startswith("- "):
            if para or quote:
                flush()
            items.append(line[2:].strip())
        elif line.startswith("  ") and items:
            items[-1] += " " + line.strip()
        elif line.startswith("#"):
            raise ValueError(f"unsupported heading level: {line}")
        else:
            if items or quote:
                flush()
            para.append(line.strip())
    flush()
    return "\n".join(out)


def build_legal(copy, pricing, seo, *, page_key: str, source: str, current: str) -> str:
    root = "../"
    body = md_to_html((CONTENT / "legal" / source).read_text(encoding="utf-8"))
    nav = copy["legalNav"]
    pages = [("acceptable-use.html", nav["acceptableUse"], "legalAcceptableUse"),
             ("privacy.html", nav["privacy"], "legalPrivacy"),
             ("terms.html", nav["terms"], "legalTerms")]
    items = "".join(
        f'<li><a href="{href}"{" aria-current=" + chr(34) + "page" + chr(34) if key == page_key else ""}>{e(label)}</a></li>'
        for href, label, key in pages)
    return f"""{head(seo, page_key, root=root, page='legal', bundle=False, styles=['tokens', 'fonts', 'type', 'layout', 'components'])}
<body>
{top_bar(copy, root=root, current=current)}
<main id="main">
<div class="vb-wrap">
<article class="vb-prose">
{body}
</article>
<nav aria-label="Legal pages" class="vb-prose__nav">
<ul class="vb-foot__links">{items}</ul>
</nav>
</div>
</main>
{footer(copy, pricing, root=root, current=current)}
</body>
</html>
"""


def build_404(copy, pricing, seo) -> str:
    nf = copy["errors"]["notFound"]
    # served for ANY missing path, at any depth: root-absolute URLs only.
    root = "/"
    fake_seo = {"site": seo["site"], "pages": {"notFound": {
        "title": f"{nf['head']} - {seo['site']['name']}", "description": nf["body"],
        "robots": "noindex,follow", "canonical": seo["site"]["origin"] + "/"}}}
    return f"""{head(fake_seo, 'notFound', root=root, page='notfound', bundle=False, styles=['tokens', 'fonts', 'type', 'layout', 'components'])}
<body>
{top_bar(copy, root=root, current='notfound')}
<main id="main">
<div class="vb-wrap vb-pagehead">
<h1>{e(nf['head'])}</h1>
<p class="vb-lede">{e(nf['body'])}</p>
<p><a class="vb-btn vb-btn--primary" href="/">{e(nf['link'])}</a></p>
</div>
</main>
{footer(copy, pricing, root=root, current='notfound')}
</body>
</html>
"""


# ------------------------------------------------------------------------------------------------

def outputs() -> dict[str, str]:
    copy, models, pricing, seo = load("copy.json"), load("models.json"), load("pricing.public.json"), load("seo.json")

    shipped_copy = strip_notes(copy)
    shipped_copy["meta"] = {"asOfFallback": copy["_readme"]["asOfFallback"], "tokens": list(copy["_readme"]["tokens"].keys())}
    dump = lambda obj: json.dumps(obj, ensure_ascii=False, indent=2) + "\n"

    files = {
        "index.html": build_index(copy, models, pricing, seo),
        "pricing.html": build_pricing(copy, models, pricing, seo),
        "404.html": build_404(copy, pricing, seo),
        "legal/acceptable-use.html": build_legal(copy, pricing, seo, page_key="legalAcceptableUse", source="acceptable-use.md", current="legal"),
        "legal/privacy.html": build_legal(copy, pricing, seo, page_key="legalPrivacy", source="privacy.md", current="legal"),
        "legal/terms.html": build_legal(copy, pricing, seo, page_key="legalTerms", source="terms-stub.md", current="legal"),
        "data/copy.json": dump(shipped_copy),
        "data/models.json": dump(models),
        "data/pricing.public.json": dump(pricing),
        "data/seo.json": dump(strip_notes(seo)),
    }
    # tidy: collapse runs of blank lines left by empty template slots
    return {k: (re.sub(r"\n{3,}", "\n\n", v) if k.endswith(".html") else v) for k, v in files.items()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="do not write; exit 1 if any output differs from disk")
    args = ap.parse_args()
    drift = 0
    for rel, text in outputs().items():
        path = RES / rel
        if args.check:
            on_disk = path.read_text(encoding="utf-8") if path.exists() else None
            if on_disk != text:
                print(f"DRIFT  {rel}")
                drift += 1
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote  {rel}  ({len(text.encode('utf-8'))} bytes)")
    return 1 if drift else 0


if __name__ == "__main__":
    sys.exit(main())
