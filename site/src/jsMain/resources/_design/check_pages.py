#!/usr/bin/env python3
"""
check_pages.py - structural checks for the design agent's static pages. Standard library only.

    python site/src/jsMain/resources/_design/check_pages.py        # exit 0 = every check passed

What it checks, per HTML file:
  - tags balance (html.parser based; void elements handled), one <h1>, <html lang>, <title>, viewport
  - no duplicate ids
  - every label[for], output[for], aria-labelledby, aria-describedby, aria-controls points at a real id
  - every local href/src resolves to a file in resources (main.css and site.js are webpack outputs and
    are allowed to be absent here), and every #fragment exists in the page it points at
  - CSP: no style="" attributes, no on*="" handlers, no <style>, no inline <script>, no remote
    script/style/font/img, no javascript: URLs
  - truth rules: no <s>/<del>/<strike>, no "% off", "save", "was $", "introductory", "limited time";
    "unlimited" only inside the one verbatim disclaimer
  - forms: field names equal the backend contract (site-api README)
And for the CSS:
  - every url() resolves; no gradients; the thirteen PaletteCss properties are #rgb/#rrggbb in both themes
  - no line-through
"""
from __future__ import annotations

import re
import sys
from html.parser import HTMLParser
from pathlib import Path

RES = Path(__file__).resolve().parents[1]
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}
BUILD_OUTPUTS = {"main.css", "site.js"}
IDREF_ATTRS = ("for", "aria-labelledby", "aria-describedby", "aria-controls")
CONTRACT_PROPS = ["--vb-ground", "--vb-ink", "--vb-rule", "--vb-glow", "--vb-accent", "--vb-accent-2", "--vb-warm",
                  "--vb-alert", "--vb-ring-well", "--vb-ring-call", "--vb-ring-top", "--vb-ring-visitor", "--vb-ring-ghost"]
FORM_FIELDS = {
    "waitlist-form": {"email", "passInterest", "website", "source"},
    "reduced-fee-form": {"contact", "pass", "rate", "circumstances", "country", "attest", "website"},
}
BANNED = [r"%\s*off", r"\bsave\b", r"\bwas \$", r"introductory", r"limited time", r"\bdonate\b", r"tax-deductible(?!\.)"]


class Page(HTMLParser):
    def __init__(self, rel: str):
        super().__init__(convert_charrefs=True)
        self.rel = rel
        self.stack: list[str] = []
        self.errors: list[str] = []
        self.ids: dict[str, int] = {}
        self.idrefs: list[tuple[str, str]] = []
        self.links: list[tuple[str, str, str]] = []
        self.h1 = 0
        self.has_title = False
        self.has_viewport = False
        self.lang = None
        self.text: list[str] = []
        self.forms: dict[str, set[str]] = {}
        self._form: str | None = None
        self._skip_text = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag not in VOID:
            self.stack.append(tag)
        if tag == "html":
            self.lang = a.get("lang")
        if tag == "h1":
            self.h1 += 1
        if tag == "title":
            self.has_title = True
        if tag == "meta" and a.get("name") == "viewport":
            self.has_viewport = True
        if tag in ("style",):
            self.errors.append("<style> element (CSP)")
        if tag == "script" and not a.get("src"):
            self.errors.append("inline <script> (CSP)")
        if tag in ("s", "del", "strike"):
            self.errors.append(f"<{tag}> strike-through element")
        if tag in ("script", "svg"):
            self._skip_text += 1 if tag == "script" else 0
        for k, v in attrs:
            if k == "style":
                self.errors.append(f"style attribute on <{tag}> (CSP)")
            if k.startswith("on"):
                self.errors.append(f"{k} handler on <{tag}> (CSP)")
        if "id" in a:
            self.ids[a["id"]] = self.ids.get(a["id"], 0) + 1
        for k in IDREF_ATTRS:
            if a.get(k):
                for ref in a[k].split():
                    self.idrefs.append((k, ref))
        for k in ("href", "src", "action"):
            if a.get(k) is not None:
                self.links.append((tag, k, a[k]))
        if tag == "form":
            self._form = a.get("id", "?")
            self.forms[self._form] = set()
        if self._form and tag in ("input", "select", "textarea") and a.get("name"):
            self.forms[self._form].add(a["name"])
        if tag == "img" and "alt" not in a:
            self.errors.append("<img> without alt")

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if tag == "script":
            self._skip_text = max(0, self._skip_text - 1)
        if tag == "form":
            self._form = None
        if not self.stack:
            self.errors.append(f"</{tag}> with nothing open")
            return
        if self.stack[-1] == tag:
            self.stack.pop()
            return
        # optional end tags the parser does not imply: report, then resync
        if tag in self.stack:
            while self.stack and self.stack[-1] != tag:
                self.errors.append(f"<{self.stack[-1]}> not closed before </{tag}>")
                self.stack.pop()
            self.stack.pop()
        else:
            self.errors.append(f"</{tag}> never opened")

    def handle_data(self, data):
        if not self._skip_text:
            self.text.append(data)


def main() -> int:
    failures: list[str] = []
    html_files = sorted(p for p in RES.rglob("*.html"))
    pages: dict[str, Page] = {}
    for path in html_files:
        rel = path.relative_to(RES).as_posix()
        page = Page(rel)
        page.feed(path.read_text(encoding="utf-8"))
        page.close()
        pages[rel] = page

    for rel, page in pages.items():
        errs = list(page.errors)
        if page.stack:
            errs.append(f"unclosed at EOF: {page.stack}")
        if page.h1 != 1:
            errs.append(f"{page.h1} <h1> elements")
        if not page.lang:
            errs.append("no <html lang>")
        if not page.has_title:
            errs.append("no <title>")
        if not page.has_viewport:
            errs.append("no viewport meta")
        for id_, n in page.ids.items():
            if n > 1:
                errs.append(f"duplicate id {id_!r} x{n}")
        for attr, ref in page.idrefs:
            if ref not in page.ids:
                errs.append(f"{attr} -> missing id {ref!r}")

        base = (RES / rel).parent
        for tag, attr, url in page.links:
            if url.startswith("javascript:"):
                errs.append(f"javascript: URL on <{tag}>")
                continue
            if re.match(r"^[a-z][a-z0-9+.-]*:", url) or url.startswith("//"):
                continue                      # absolute URL: remote subresources are caught further down
            if url.startswith("/api/"):
                continue
            target, _, frag = url.partition("#")
            target = target.split("?")[0]
            if target == "":
                target_rel = rel
            elif target.startswith("/"):
                target_rel = target.lstrip("/") or "index.html"
            else:
                target_rel = (base / target).resolve().relative_to(RES.resolve()).as_posix()
            if target_rel in BUILD_OUTPUTS:
                continue
            if not (RES / target_rel).is_file():
                errs.append(f"broken reference {url!r}")
                continue
            if frag and target_rel in pages and frag not in pages[target_rel].ids:
                errs.append(f"fragment #{frag} not found in {target_rel}")

        # remote subresources (anchors to other sites are fine; loading from them is not)
        raw = (RES / rel).read_text(encoding="utf-8")
        for m in re.finditer(r"<(script|img|iframe|source|video|audio)\b[^>]*\bsrc=\"(https?:)?//", raw):
            errs.append(f"remote subresource: {m.group(0)[:60]}")
        for m in re.finditer(r"<link\b[^>]*>", raw):
            tag_text = m.group(0)
            if re.search(r'href="(https?:)?//', tag_text) and 'rel="canonical"' not in tag_text:
                errs.append(f"remote <link>: {tag_text[:80]}")

        text = " ".join(page.text)
        for pat in BANNED:
            for m in re.finditer(pat, text, flags=re.I):
                ctx = text[max(0, m.start() - 60): m.end() + 30]
                # the reviewed copy denies these things once each; a denial is not a claim
                if "not a donation" in ctx or "not tax-deductible" in ctx or "not an introductory" in ctx:
                    continue
                errs.append(f"banned phrase {m.group(0)!r}: ...{ctx.strip()}...")
        for m in re.finditer(r"unlimited", text, flags=re.I):
            ctx = text[max(0, m.start() - 20): m.end()]
            if "is not unlimited" not in ctx:
                errs.append(f"'unlimited' outside the verbatim disclaimer: ...{ctx}")

        for form_id, expected in FORM_FIELDS.items():
            if form_id in page.forms and page.forms[form_id] != expected:
                errs.append(f"form {form_id}: fields {sorted(page.forms[form_id])} != contract {sorted(expected)}")

        status = "ok  " if not errs else "FAIL"
        print(f"{status} {rel}: {len(page.ids)} ids, {len(page.links)} refs, {len(page.idrefs)} idrefs")
        for err in errs:
            print(f"       - {err}")
            failures.append(f"{rel}: {err}")

    # ---- CSS ----
    for css in sorted((RES / "styles").glob("*.css")):
        if css.name == "engine.css":       # bringup's engine demo stylesheet: not loaded by any page here
            continue
        text = css.read_text(encoding="utf-8")
        body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        errs = []
        for m in re.finditer(r"url\(\s*[\"']?([^\"')]+)[\"']?\s*\)", body):
            u = m.group(1)
            if u.startswith("data:"):
                continue
            if re.match(r"^[a-z]+:", u) or u.startswith("//"):
                errs.append(f"remote url({u})")
            elif not (css.parent / u).resolve().is_file():
                errs.append(f"broken url({u})")
        if "gradient(" in body:
            errs.append("gradient on chrome")
        if "line-through" in body:
            errs.append("line-through")
        if "@import" in body and css.name != "api-page.css":
            errs.append("@import")
        if css.name == "tokens.css":
            blocks = re.findall(r"(:root[^{]*)\{([^}]*)\}", body)
            themed = [b for sel, b in blocks if "data-theme" in sel]
            if len(themed) != 2:
                errs.append(f"expected 2 theme blocks, found {len(themed)}")
            for i, b in enumerate(themed):
                for prop in CONTRACT_PROPS:
                    m = re.search(re.escape(prop) + r"\s*:\s*([^;]+);", b)
                    if not m:
                        errs.append(f"theme block {i}: {prop} missing")
                    elif not re.fullmatch(r"#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})", m.group(1).strip()):
                        errs.append(f"theme block {i}: {prop} = {m.group(1).strip()!r} is not #rgb/#rrggbb")
        print(f"{'ok  ' if not errs else 'FAIL'} styles/{css.name}")
        for err in errs:
            print(f"       - {err}")
            failures.append(f"{css.name}: {err}")

    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
