#!/usr/bin/env python3
"""Build the price-tracked verdantbloom.bar site from the live sources in web/sources/.

Every price, limit and wait on the pages is computed from what the gateway actually enforces:
  sources/gateway.json   vbgate's rules (levels, models, voice caps)       <- /opt/vbgate/gateway.json
  sources/rate_card.json the ledger's current rate card                    <- ledger.rate_card
  sources/taps.json      podctl's served models (public fields)           <- /opt/podctl/taps.json
  sources/eta.json       real cold-start history                          <- podctl /eta/<tap>
  sources/model.json     hand-written facts about the model (checked against taps.json)
Refresh them with export_sources.sh, rebuild, check, deploy. Nothing on a page is typed by hand twice.

  python3 web/build.py            # writes web/dist/
  python3 web/check.py            # must print 0 problems
"""
from __future__ import annotations

import html
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

WEB = Path(__file__).resolve().parent
SRC = WEB / "sources"
RES = WEB.parent / "site" / "src" / "jsMain" / "resources"   # styles, fonts, legal from the original site
OUT = WEB / "dist"

API = "https://api.verdantbloom.bar/v1"
ACCOUNT = "/api/account"
LEVEL_ORDER = ["walkin", "well", "call", "top", "reserve", "private"]
LEVEL_NAME = {"walkin": "Walk-In", "well": "Well", "call": "Call", "top": "Top Shelf",
              "reserve": "Reserve", "private": "Private Stock"}


def load(name):
    return json.loads((SRC / name).read_text())


g, cards, taps, eta, model = (load(n) for n in ("gateway.json", "rate_card.json", "taps.json", "eta.json", "model.json"))
exported = (SRC / "exported_at").read_text().strip()
card = {c["rate_class"]: c for c in cards}

# ---------------------------------------------------------------- facts, all derived
TAP = model["tap"]
assert set(g["taps"]) == {TAP}, f"gateway serves {sorted(g['taps'])}, the site describes {TAP}: update model.json"
assert taps[TAP]["weights_size"] == model["hf_file_size"], "taps.json weights_size != model.json hf_file_size: the served weights changed"
rc = card[g["taps"][TAP]["rate_class"]]
levels = [(lid, g["levels"][lid]) for lid in LEVEL_ORDER]
for lid, lv in levels:
    assert sorted(lv["models"]) == sorted([TAP, g["voice"]["model"]]), f"{lid} models {lv['models']}"
    assert not lv.get("opt_ins"), f"{lid} allows opt-ins: the site doesn't sell any"
assert not g.get("opt_ins"), "gateway.json lists opt-in models: the site doesn't sell any"
thinking_default = g["taps"][TAP].get("thinking")

in_per_m = rc["in_uusd_per_mtok"] / 1e6
out_per_m = rc["out_uusd_per_mtok"] / 1e6
per_s = rc["card_uusd_per_hour"] / 1e6 / 3600 * rc["markup_permille"] / 1000      # $ per GPU-second, marked up
idle_cap_s = rc["idle_cap_ms"] / 1000
cold_s = rc["cold_start_ms"] / 1000
warm_min = per_s * idle_cap_s                     # first request after a minute away, alone on the pod
cold_min = per_s * (idle_cap_s + cold_s)          # the request that wakes the pod
shared_min = per_s * idle_cap_s / rc["k_max"]     # the same, with the pod shared k_max ways
boot_med = round(eta["boot_median_s"])
boot_p90 = round(eta["boot_p90_s"])
idle_min = round(eta["idle_s"] / 60)
retention = load("retention.json")
MAX_LIFE_H = round(retention["max_life_s"] / 3600)
SESSION_DAYS = retention["session_ttl_days"]
voice = g["voice"]


def usd(x, cents_below=1.0):
    if x < cents_below:
        c = x * 100
        return f"{c:.1f}¢" if c < 10 else f"{c:.0f}¢"
    return f"${x:,.2f}".replace(".00", "")


def n(x):
    return f"{x:,}".replace(",", " ") if x >= 10000 else f"{x:,}"


def k(tokens):
    return f"{tokens // 1024}k" if tokens % 1024 == 0 else n(tokens)


def e(s):
    return html.escape(str(s), quote=True)


asof = datetime.fromisoformat(exported.replace("Z", "+00:00")).astimezone(timezone.utc)
ASOF_ISO = asof.strftime("%Y-%m-%d")
ASOF_TXT = asof.strftime("%-d %B %Y")

# ---------------------------------------------------------------- shared chrome
NAV = [("index.html", "THE TAP"), ("index.html#levels", "LEVELS"), ("pricing.html", "PRICES"), (ACCOUNT, "OPEN A TAB")]


def page(name, title, desc, body, current=None, depth=0):
    up = "../" * depth
    nav = "".join(
        f'<li><a href="{up if not href.startswith("/") else ""}{href}"'
        + (' aria-current="page"' if href == current else "") + f">{label}</a></li>"
        for href, label in NAV)
    return f"""<!DOCTYPE html>
<html lang="en" data-theme="absinthe-abyss" data-page="{e(name)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{e(title)}</title>
<meta name="description" content="{e(desc)}">
<meta name="robots" content="index,follow">
<meta name="theme-color" content="#020a07">
<meta name="color-scheme" content="dark light">
<meta name="referrer" content="same-origin">
<meta property="og:type" content="website">
<meta property="og:site_name" content="verdantbloom.bar">
<meta property="og:title" content="{e(title)}">
<meta property="og:description" content="{e(desc)}">
<link rel="preload" href="{up}fonts/archivo-black-latin-400-normal.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{up}styles/tokens.css">
<link rel="stylesheet" href="{up}styles/fonts.css">
<link rel="stylesheet" href="{up}styles/type.css">
<link rel="stylesheet" href="{up}styles/layout.css">
<link rel="stylesheet" href="{up}styles/components.css">
<link rel="stylesheet" href="{up}styles/print.css" media="print">
</head>
<body>
<header class="vb-top">
<div class="vb-wrap vb-top__row">
<a class="vb-wordmark" href="{up}index.html">VERDANTBLOOM.BAR</a>
<nav class="vb-nav" aria-label="Site"><ul>{nav}</ul></nav>
</div>
</header>
<main id="main">
{body}
</main>
<footer class="vb-foot">
<div class="vb-wrap vb-foot__grid">
<div class="vb-stack">
<p class="vb-foot__age"><strong>18+ ONLY.</strong> A developer API for fiction and writing tools. Not for adult-content services. You are responsible for what you generate. The acceptable-use policy applies.</p>
<p>Prices and limits as of <time class="vb-asof" datetime="{ASOF_ISO}">{ASOF_TXT}</time>, taken from the live rate card and the gateway's own rules. When they change, this page changes with them.</p>
<p><strong>verdantbloom.bar is a business, not a charity.</strong></p>
</div>
<nav aria-label="Footer"><ul class="vb-foot__links">
<li><a href="{up}index.html">THE TAP</a></li>
<li><a href="{up}pricing.html">PRICES</a></li>
<li><a href="{ACCOUNT}">OPEN A TAB</a></li>
<li><a href="{up}legal/acceptable-use.html">ACCEPTABLE USE</a></li>
<li><a href="{up}legal/privacy.html">PRIVACY</a></li>
<li><a href="{up}legal/api-privacy.html">API PRIVACY</a></li>
<li><a href="{up}legal/terms.html">TERMS</a></li>
<li><a href="{up}legal/law-enforcement.html">LAW ENFORCEMENT</a></li>
</ul></nav>
</div>
</footer>
</body>
</html>
"""


# ---------------------------------------------------------------- blocks
def levels_table(caption_id):
    rows = []
    for lid, lv in levels:
        rows.append(
            f'<tr data-level="{lid}"><th scope="row">{e(LEVEL_NAME[lid]).upper()}</th>'
            f'<td class="vb-num">{"free" if lv["tab_usd"] == 0 else "$" + str(lv["tab_usd"]) + " / month"}</td>'
            f'<td class="vb-num">{n(lv["daily"])}</td>'
            f'<td class="vb-num">{lv["rpm"]}</td>'
            f'<td class="vb-num">{lv["parallel"]}</td>'
            f'<td class="vb-num">{k(lv["context"])}</td>'
            f'<td class="vb-num">{n(lv["max_tokens"])}</td>'
            f'<td>{"only while warm" if lv.get("wakes") is False else "yes"}</td></tr>')
    return f"""<div class="vb-board-scroll" tabindex="0" role="region" aria-labelledby="{caption_id}">
<table class="vb-board">
<caption id="{caption_id}">THE LEVELS</caption>
<thead><tr><th scope="col">LEVEL</th><th scope="col">PRICE</th><th scope="col">REQUESTS / DAY</th><th scope="col">PER MINUTE</th><th scope="col">AT ONCE</th><th scope="col">CONTEXT</th><th scope="col">MAX REPLY</th><th scope="col">WAKES THE TAP</th></tr></thead>
<tbody>
{chr(10).join(rows)}
</tbody>
</table>
</div>"""


def rates_table():
    return f"""<div class="vb-board-scroll" tabindex="0" role="region" aria-labelledby="rates-caption">
<table class="vb-board">
<caption id="rates-caption">WHAT THE TAB IS SPENT AT</caption>
<thead><tr><th scope="col">ITEM</th><th scope="col">PRICE</th></tr></thead>
<tbody>
<tr><th scope="row">INPUT TOKENS</th><td class="vb-num">{usd(in_per_m)} per million</td></tr>
<tr><th scope="row">OUTPUT TOKENS</th><td class="vb-num">{usd(out_per_m)} per million</td></tr>
<tr><th scope="row">MINIMUM, WARM TAP</th><td class="vb-num">about {usd(warm_min)} a request</td></tr>
<tr><th scope="row">MINIMUM, WAKING THE TAP</th><td class="vb-num">about {usd(cold_min)} a request</td></tr>
<tr><th scope="row">SPEECH</th><td class="vb-num">free</td></tr>
</tbody>
</table>
</div>"""


MODEL_CARD = f"""<article class="vb-pour" id="pour/{e(TAP)}" data-pour-id="{e(TAP)}">
<header class="vb-pour__head">
<p class="vb-pour__code"><code>{e(TAP)}</code></p>
<h3 class="vb-pour__name">{e(model["display_name"])}</h3>
</header>
<dl class="vb-facts">
<div class="vb-facts__row"><dt>BASE</dt><dd>{e(model["base"])}</dd></div>
<div class="vb-facts__row"><dt>QUANT</dt><dd>{e(model["quant"])}</dd></div>
<div class="vb-facts__row"><dt>THINKING</dt><dd>{"off unless you ask" if thinking_default is False else "on"}</dd></div>
<div class="vb-facts__row"><dt>COLD POUR</dt><dd>about {boot_med} s, up to {boot_p90} s</dd></div>
</dl>
<p class="vb-pour__note">{e(model["note"])}</p>
<p class="vb-pour__order"><code>"model": "{e(TAP)}"</code></p>
<p class="vb-pour__links"><a href="https://huggingface.co/{e(model["hf_repo"])}" rel="noopener noreferrer external">MODEL CARD ON HUGGING FACE</a></p>
</article>"""

walkin = g["levels"]["walkin"]

INDEX = f"""<div class="vb-wrap vb-pagehead">
<h1>OPEN A TAB.</h1>
<p class="vb-lede">A developer API for fiction and writing tools. One open-weight model behind an OpenAI-compatible endpoint, with speech. Pay by the month, billed by the token.</p>
<p><a class="vb-btn vb-btn--primary" href="{ACCOUNT}">OPEN A TAB</a> <a class="vb-btn" href="pricing.html">SEE THE PRICES</a></p>
</div>

<section id="for" class="vb-section" aria-labelledby="for-title">
<div class="vb-wrap vb-stack">
<h2 id="for-title">WHAT IT'S FOR</h2>
<ul class="vb-stack">
<li><strong>Writing tools.</strong> Drafting, rewriting and continuing long-form fiction: novels, short stories, scripts, interactive fiction.</li>
<li><strong>Games and worlds.</strong> NPC dialogue, quest and lore text, world-building, narrated text adventures.</li>
<li><strong>Your own software.</strong> Any OpenAI client, a key per app, metered per request, nothing stored.</li>
</ul>
<p class="vb-fine">Not for adult-content services: see the <a href="legal/acceptable-use.html">acceptable-use policy</a>.</p>
</div>
</section>

<section id="tap" class="vb-section vb-section--tint-2" aria-labelledby="tap-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="tap-title">ON TAP</h2>
<p>One model today, at every level. More join the list when they're measured; the levels then differ by bottle as well as by room.</p>
</div>
{MODEL_CARD}
<p class="vb-fine">Speech comes with it: {e(model["voice"]["name"])}, {model["voice"]["voices"]} voices, free at every level, {n(voice["max_input_chars"])} characters a request and {n(voice["daily_chars"])} a day. It plays while a chat pour has the tap running; it never wakes a cold one.</p>
</div>
</section>

<section id="levels" class="vb-section" aria-labelledby="levels-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="levels-title">THE LEVELS</h2>
<p>A level is a monthly tab: the price is the money on it, spent at the rates below, reset at the start of each month. Higher levels add room: more requests, more at once, longer context, longer replies.</p>
</div>
{levels_table("levels-caption")}
<p class="vb-fine">Walk-In is free and pours only while the tap is already running: it never starts a GPU. When the tap is cold it answers "changing the keg" and that request doesn't count. One free account per mailbox.</p>
</div>
</section>

<section id="cold" class="vb-section vb-section--tint-2" aria-labelledby="cold-title">
<div class="vb-wrap vb-stack">
<h2 id="cold-title">WARM / COLD</h2>
<p>The tap is a rented GPU that switches off when nobody is drinking. The first pour after that wakes it: about {boot_med} seconds, sometimes up to {boot_p90} (measured over the last {eta["boot_samples"]} wakes). It stays warm {idle_min} minutes after the last request. People pouring at the same hour keep it warm for each other, and share its cost.</p>
<p>Ask how long a pour will take before you send it: <code>GET {API}/taps/{e(TAP)}/eta</code>. It never wakes the tap.</p>
</div>
</section>

<section id="api" class="vb-section" aria-labelledby="api-title">
<div class="vb-wrap vb-stack">
<h2 id="api-title">THE ENDPOINT</h2>
<p>Any OpenAI client works. Base URL <code>{API}</code>, model <code>{e(TAP)}</code>, your key from the tab page.</p>
<pre class="vb-code"><code>curl {API}/chat/completions \\
  -H "Authorization: Bearer vb-live-…" \\
  -H "Content-Type: application/json" \\
  -d '{{"model": "{e(TAP)}", "messages": [{{"role": "user", "content": "Begin."}}]}}'</code></pre>
<p class="vb-fine">Streaming works. <code>GET /v1/models</code> lists what your key may pour. Speech: <code>POST /v1/audio/speech</code> with <code>"model": "voice"</code>. Turn thinking on with <code>"chat_template_kwargs": {{"enable_thinking": true}}</code>; thinking is billed as output.</p>
<p><a class="vb-btn vb-btn--primary" href="{ACCOUNT}">OPEN A TAB</a></p>
</div>
</section>"""

PRICING = f"""<div class="vb-wrap vb-pagehead">
<h1>PRICES</h1>
<p class="vb-lede">Everything on one page. As of <time class="vb-asof" datetime="{ASOF_ISO}">{ASOF_TXT}</time>, straight from the live rate card. Best guess at cost; they may move, with notice.</p>
</div>

<section id="levels" class="vb-section vb-section--tint-2" aria-labelledby="levels-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="levels-title">THE LEVELS</h2>
<p>The price is the money on your tab for the month. Every level pours the same model, <code>{e(TAP)}</code>, and speech. Unused tab doesn't roll over.</p>
</div>
{levels_table("levels-caption")}
<ul class="vb-stack">
<li><strong>REQUESTS / DAY, PER MINUTE, AT ONCE:</strong> per account, every key on it shares them. Days reset at 00:00 UTC.</li>
<li><strong>CONTEXT:</strong> your messages plus the room left for the reply, in tokens. Images count about 512 each.</li>
<li><strong>MAX REPLY:</strong> a longer <code>max_tokens</code> is quietly lowered to this, never an error.</li>
<li><strong>WAKES THE TAP:</strong> Walk-In pours only while the tap is already running.</li>
</ul>
</div>
</section>

<section id="rates" class="vb-section" aria-labelledby="rates-title">
<div class="vb-wrap vb-stack-lg">
<div class="vb-section__head">
<h2 id="rates-title">THE RATES</h2>
<p>Each request costs its tokens, or the minimum, whichever is more. Your client resends the whole chat every turn, and every turn is billed as input. Thinking is billed as output.</p>
</div>
{rates_table()}
<p class="vb-fine">The minimum is the GPU time a request holds: its own generation time, plus up to {round(idle_cap_s)} s of the time the tap sat warm since your last request, split between everyone pouring at that hour, plus {round(cold_s)} s when your request is the one that wakes it. At {usd(per_s * 3600)} per GPU-hour that comes to about {usd(warm_min)} for a lone request after a minute away, about {usd(shared_min)} when {rc["k_max"]} people share the tap, and about {usd(cold_min)} for the request that wakes it.</p>
</div>
</section>

<section id="faq" class="vb-section vb-section--tint-2" aria-labelledby="faq-title">
<div class="vb-wrap vb-stack">
<h2 id="faq-title">STRAIGHT ANSWERS</h2>
<dl class="vb-stack">
<dt>Can I pay yet?</dt><dd>Not yet. Open a tab now and get a key; the level buttons switch on when payments open.</dd>
<dt>Why did my first pour take so long?</dt><dd>The tap was cold: about {boot_med} s to wake, up to {boot_p90} s. It stays warm {idle_min} minutes after the last request.</dd>
<dt>Is there a bigger context?</dt><dd>The model can hold far more; the levels stop at {k(max(lv["context"] for _, lv in levels))} until longer prompts are measured.</dd>
<dt>Top-ups?</dt><dd>Not sold yet. When your tab is dry, it refills at the start of the month.</dd>
<dt>Do I get a GPU to myself?</dt><dd>No. The tap is shared; that's what keeps the minimum low.</dd>
<dt>Is this an adult site?</dt><dd>No. It is a developer API for fiction and writing tools, 18 and over. Building an adult-content service on it is not allowed: see the <a href="legal/acceptable-use.html">acceptable-use policy</a>.</dd>
</dl>
<p><a class="vb-btn vb-btn--primary" href="{ACCOUNT}">OPEN A TAB</a></p>
</div>
</section>"""

GONE = lambda what: f"""<div class="vb-wrap vb-pagehead vb-stack">
<h1>{what}</h1>
<p class="vb-lede">This page described a ten-model list that isn't served. What is served, and what it costs, is on <a href="index.html#tap">the tap</a> and <a href="pricing.html">the prices</a>.</p>
</div>"""

NOTFOUND = """<div class="vb-wrap vb-pagehead vb-stack">
<h1>NOT ON TAP.</h1>
<p class="vb-lede">Nothing here. <a href="/index.html">Back to the bar.</a></p>
</div>"""


def build():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    shutil.copytree(RES / "styles", OUT / "styles")
    shutil.copytree(RES / "fonts", OUT / "fonts")
    (OUT / "legal").mkdir()
    shutil.copy(RES / "legal" / "privacy.html", OUT / "legal" / "privacy.html")
    # The old site's legal pages: add the API privacy link to their own footers, and replace the website
    # privacy page's "the API has no policy yet" lines now that it does (legal/api-privacy.html).
    def legal_fix(name, text):
        foot = '<li><a href="privacy.html"'
        assert foot in text, f"{name}: footer privacy link moved: update build.py"
        text = text.replace('PRIVACY</a></li><li><a href="terms.html"',
                            'PRIVACY</a></li><li><a href="api-privacy.html">API PRIVACY</a></li><li><a href="terms.html"', 1)
        text = text.replace("TERMS (STUB - NEEDS COUNSEL BEFORE PAYMENTS OPEN)", "TERMS").replace("TERMS (STUB)", "TERMS")
        old_age = ("<strong>18+ ONLY.</strong>A fiction-writing and developer API. Some of these bottles write graphic horror "
                   "and violence; their makers&#x27; labels say so. You are responsible for what you generate. The "
                   "acceptable-use policy applies.")
        if old_age in text:
            text = text.replace(old_age, "<strong>18+ ONLY.</strong> A developer API for fiction and writing tools. Not for "
                                         "adult-content services. You are responsible for what you generate. The "
                                         "acceptable-use policy applies.")
        if name == "privacy.html":
            for old, new in (
                ("<p><strong>It does not cover the API.</strong> What the API stores will be written down in its own policy. "
                 "Until then, nothing is promised about it, here or anywhere on this site.</p>",
                 '<p><strong>It does not cover the API.</strong> The API has its own page: '
                 '<a href="api-privacy.html">API privacy</a>. In one line: we do not store or log what you write to the '
                 'model, or what it writes back.</p>'),
                ("<p>This page will change when the API gets its own policy. The date at the top changes with it.</p>",
                 "<p>This page will change. The date at the top changes with it.</p>")):
                assert old in text, "privacy.html API sentence changed: update build.py"
                text = text.replace(old, new)
        return text
    p = OUT / "legal" / "privacy.html"
    p.write_text(legal_fix("privacy.html", p.read_text()))
    import acceptable_use as au
    (OUT / "legal" / "acceptable-use.html").write_text(
        page("legal", "Acceptable use - verdantbloom.bar",
             "What the verdantbloom.bar API may and may not be used for. A developer API for fiction and writing tools; not for adult-content services.",
             au.BODY.format(asof=ASOF_TXT), depth=1))
    # Real terms (terms.py) replace the old stub; every number from the live sources.
    import api_privacy as ap
    import terms as tm
    address_clause_txt = (", " + ", ".join(ap.ADDRESS_LINES)) if ap.ADDRESS_FINAL else ""
    operator_sentence = (f"verdantbloom.bar is operated by {e(ap.OPERATOR_NAME)}{e(address_clause_txt)}." if ap.OPERATOR_NAME
                         else f"verdantbloom.bar (&ldquo;we&rdquo;) is a business{e(address_clause_txt)}.")
    paid = [(lid, lv) for lid, lv in levels if lv["tab_usd"] > 0]
    level_prices = ", ".join(f"{LEVEL_NAME[lid]} ${lv['tab_usd']}" for lid, lv in paid)
    terms_body = tm.BODY.format(asof=ASOF_TXT, operator_sentence=operator_sentence, tap=e(TAP), level_prices=level_prices,
                                rate_in=usd(in_per_m), rate_out=usd(out_per_m), min_warm=usd(warm_min),
                                min_cold=usd(cold_min), idle_min=idle_min, boot_med=boot_med, boot_p90=boot_p90)
    (OUT / "legal" / "terms.html").write_text(
        page("legal", "Terms - verdantbloom.bar",
             "The terms for verdantbloom.bar and its API: accounts, levels, the tab, renewal, refunds, and what is not promised.",
             terms_body, depth=1))
    # API privacy notice (api_privacy.py): numbers come from the same live sources as the rest.
    api_body = ap.BODY.format(asof=ASOF_TXT, operator_sentence=operator_sentence, idle_min=idle_min,
                              max_life_h=MAX_LIFE_H, session_days=SESSION_DAYS)
    import law_enforcement as lawe
    (OUT / "legal" / "law-enforcement.html").write_text(
        page("legal", "Law enforcement and legal requests - verdantbloom.bar",
             "How verdantbloom.bar answers subpoenas, court orders, warrants and emergency requests, and what it holds: "
             "no prompts or replies, ever.",
             lawe.BODY.format(asof=ASOF_TXT), depth=1))
    (OUT / "legal" / "api-privacy.html").write_text(
        page("legal", "API privacy - verdantbloom.bar",
             "What the verdantbloom.bar API keeps: accounts, keys, meters and money. Never the text of a request or a reply.",
             api_body, depth=1))
    pages = {
        "index.html": page("index", "verdantbloom.bar - open a tab",
                           f"A developer API for fiction and writing tools: one open-weight model behind an OpenAI-compatible endpoint. Levels from free to ${max(lv['tab_usd'] for _, lv in levels)} a month.",
                           INDEX, "index.html"),
        "pricing.html": page("pricing", "Prices - verdantbloom.bar",
                             f"Six levels, free to ${max(lv['tab_usd'] for _, lv in levels)} a month. {usd(in_per_m)} in / {usd(out_per_m)} out per million tokens.",
                             PRICING, "pricing.html"),
        "pours.html": page("pours", "The pour list - verdantbloom.bar", "What is on tap.", GONE("THE POUR LIST")),
        "fine-print.html": page("fine-print", "The fine print - verdantbloom.bar", "Where the fine print went.", GONE("THE FINE PRINT")),
        "404.html": page("notfound", "Not found - verdantbloom.bar", "Not found.", NOTFOUND),
    }
    for name, doc in pages.items():
        (OUT / name).write_text(doc)
    facts = {"as_of": exported, "model": TAP, "in_usd_per_mtok": in_per_m, "out_usd_per_mtok": out_per_m,
             "min_warm_usd": round(warm_min, 4), "min_cold_usd": round(cold_min, 4), "min_shared_usd": round(shared_min, 4),
             "cold_start_median_s": boot_med, "cold_start_p90_s": boot_p90,
             "levels": {lid: lv for lid, lv in levels}}
    (OUT / "facts.json").write_text(json.dumps(facts, indent=1) + "\n")
    print(f"built {OUT}: {', '.join(sorted(pages))} + legal/, styles/, fonts/, facts.json")
    print(f"  {TAP}: {usd(in_per_m)} in / {usd(out_per_m)} out per M; minimum {usd(warm_min)} warm, "
          f"{usd(shared_min)} shared x{rc['k_max']}, {usd(cold_min)} waking; cold start {boot_med} s (p90 {boot_p90})")


if __name__ == "__main__":
    build()
