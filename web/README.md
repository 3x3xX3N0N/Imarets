# web/ - the price-tracked verdantbloom.bar site

Every price, limit and wait on the site is generated from what the gateway actually enforces, so the
site can't drift from production. Replaces the hand-written ten-model pages (still in `site/`, unused).

| file | what |
|---|---|
| `export_sources.sh` | pulls the live sources from the box: vbgate `gateway.json`, the ledger's current `rate_card`, podctl's served models (public fields), real cold-start history, idle timeout |
| `sources/` | those exports (committed, so a build is reproducible) + `model.json` (hand-written model facts, checked against the served weights' size) |
| `build.py` | writes `dist/`: `index.html`, `pricing.html`, `404.html`, legal pages, the original styles/fonts, `facts.json` |
| `check.py` | every level row equals the gateway's numbers, rates and minimums equal the rate card, no text from the old ten-model site, no scripts or inline styles, no broken links. Must print 0 problems |
| `deploy_web.sh` | export, build, check, back up the live tree, rsync into it, smoke the URLs. `--rollback <ts>` restores a backup |

Change a price or a limit in production (rate card / `gateway.json` / `contracts/levels.md`), then run
`bash web/deploy_web.sh`. The site follows.
