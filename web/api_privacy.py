"""API privacy notice (legal/api-privacy.html). DRAFT until the operator approves it and a lawyer has read it.

Every sentence here was checked against the running code on 2026-10-03 (see the verdantbloom super-repo
RULES.md rule 6 and host/podctl/test_private.py). If the code changes what it stores, this file changes
FIRST. Placeholders in {braces} are filled by build.py; ADDRESS stays visibly unfinished until the
PO box number arrives.
"""

# The PO box number and the 4-digit box extension are not known yet. Keep the placeholder visible
# (check.py fails on it once ADDRESS_FINAL is True) rather than printing a made-up number.
ADDRESS_LINES = ["PO Box [number pending]", "Bryn Athyn, PA 19009-[box extension pending]"]
ADDRESS_FINAL = False

BODY = """<div class="vb-wrap">
<article class="vb-prose">
<h1>API privacy</h1>
<p>As of {asof}. <strong>Draft.</strong> This page covers the API at <code>api.verdantbloom.bar</code>: your account,
your keys, your tab, and every request you send. The <a href="privacy.html">website privacy page</a> covers the
landing page and its forms.</p>

<h2>1. The short version</h2>
<p><strong>We do not store or log what you write, or what the model writes back.</strong> Not the prompt, not the
reply, not a summary, not a sample, not a hash of either. We keep what we need to run your account and charge your
tab: who you are, which key, when, how many tokens, how long the GPU was busy, and what it cost.</p>

<h2>2. Who</h2>
<p>verdantbloom.bar, {address}. Contact: <code>bloom@verdantbloom.bar</code>.</p>

<h2>3. What we never keep</h2>
<ul>
<li>The text of your requests: prompts, messages, system prompts, images or files you send.</li>
<li>The text of the replies, streamed or not, and any speech audio the voice tap makes.</li>
</ul>
<p>Your request passes through our gateway to a rented GPU, the model answers, and the answer passes back. Nothing in
that path writes the text down. That is enforced in code, not only promised: the GPU server is refused if it is
started in any mode that would print or record text, and anyone who wants to change that must change this page first.</p>
<p>While a request is being answered, the GPU server keeps a working copy of the conversation in memory, and may keep a
cache of it on its own disk so a follow-up request starts faster. That copy is only used to answer you. It is
deleted when the server is shut down, which happens after {idle_min} minutes without requests and at most
{max_life_h} hours after it started.</p>

<h2>4. What we keep, and why</h2>
<table class="vb-table">
<thead><tr><th>What</th><th>Why</th><th>How long</th></tr></thead>
<tbody>
<tr><td>Your email address, account id, level, and whether the account is active</td><td>To log you in, run your level's limits, and write to you about your account</td><td>While the account exists</td></tr>
<tr><td>Your keys: name, first few characters, when made, when last used, when revoked. Never the key itself: only a one-way hash.</td><td>To check a key on every request, and to show you your keys</td><td>While the account exists</td></tr>
<tr><td>Each request's meter: time, key, model, tokens in and out, GPU busy and idle milliseconds, the charge, and whether it succeeded</td><td>To charge your tab correctly and show you your usage</td><td>As long as tax and accounting rules require (see 6)</td></tr>
<tr><td>Your tab: payments in, charges out, refunds</td><td>It is your tab</td><td>As long as tax and accounting rules require</td></tr>
<tr><td>Daily request and speech counters</td><td>Your level's daily limits</td><td>Per day; old days are not used</td></tr>
<tr><td>Login links and sessions, as one-way hashes, with a salted hash of the network address that asked</td><td>So a link works once, and to slow down abuse</td><td>Links expire within the hour; sessions after {session_days} days</td></tr>
</tbody>
</table>
<p>None of that contains text you wrote, apart from your email address and the names you give your keys.</p>

<h2>5. Who else handles it</h2>
<ul>
<li><strong>Stripe</strong> handles card payments. We get back that a payment happened, its amount and its reference.
We never see or store your card number.</li>
<li><strong>SendGrid (Twilio)</strong> delivers login links and account mail, so it handles your email address.</li>
<li><strong>Vultr</strong> rents us the server the gateway, the account database and the website run on.</li>
<li><strong>Cloudflare</strong> answers for our domain names.</li>
<li><strong>RunPod</strong> (and, when RunPod has no GPU free, <strong>Vast.ai</strong>) rents us the GPU servers the model
runs on. Your request and its reply travel to and from those servers. We do not store the text and we do not let
them log it, but they operate the machines. We cannot promise what a hosting provider can technically see on its own
hardware, and we will not pretend to.</li>
<li><strong>Google Cloud Storage</strong> holds our usage records: counts, timings and costs per GPU server. No text,
and no email addresses.</li>
</ul>
<p>We do not sell any of it. We do not use it to train models. We run no analytics and no advertising on the API.</p>

<h2>6. How long</h2>
<p>Account, key and tab records last while your account exists. Billing records (meters, payments, refunds) are kept
as long as tax and accounting rules require us to keep them, then deleted. <em>[Draft: the exact period, likely seven
years in the US, to be confirmed.]</em></p>

<h2>7. Your choices</h2>
<p>Write to <code>bloom@verdantbloom.bar</code> from your account's address to see what we hold about you, correct it,
or close your account. Closing revokes every key at once. Records we must keep for accounting stay, without anything
you wrote, because we never had it.</p>

<h2>8. When we would look</h2>
<p>We cannot read your past requests, because we do not have them. If a key is reported for breaking the
<a href="acceptable-use.html">acceptable-use policy</a>, we act on the report and the account's meter, not on its text.
If the law requires us to hand over what we hold, what we hold is in section 4.</p>

<h2>9. Age</h2>
<p>The API is for people 18 or older.</p>

<h2>10. Changes</h2>
<p>If we ever change what is kept about requests, this page changes <strong>before</strong> the code does, and the date
at the top changes with it. A change that would keep any text you write would be announced to account holders by
email first.</p>
</article>
</div>
"""
