"""Builds the ChurConf Sydney 2026 deck from the conference template.

Usage:
    python -m venv v && v/bin/pip install python-pptx
    v/bin/python build_deck.py <template.pptx> <repo root> <out.pptx>

The template's title, image-with-caption and closing slides are kept and filled in. Its other sample
slides are used only as formatting prototypes (column headers, code lines, captions) and then removed.
"""
import copy
import sys

from pptx import Presentation
from pptx.oxml.ns import qn

template, repo, out = sys.argv[1:4]
p = Presentation(template)
S = list(p.slides)
TITLE, HOWTO, SECTION_S, CONTENT_S, TWO_COL_S, STATEMENT_S, CODE_S, IMAGE_S, CLOSING = S
LAYOUT = {l.name: l for l in TITLE.slide_layout.slide_master.slide_layouts}


def paras(shape):
    return shape.text_frame._txBody.findall(qn("a:p"))


def ph(slide, idx):
    return next(s for s in slide.placeholders if s.placeholder_format.idx == idx)


# Formatting prototypes taken from the template's sample slides
P_COL_HEAD, P_COL_BODY = paras(ph(TWO_COL_S, 1))[:2]
_code = paras(ph(CODE_S, 1))
P_CODE_COMMENT, P_CODE, P_CODE_HL = _code[0], _code[1], _code[-1]
_cap = [sh for sh in IMAGE_S.shapes if sh.has_text_frame and "Caption" in sh.text_frame.text][0]
P_CAP_HEAD, P_CAP_BODY = paras(_cap)[:2]
_bul = paras(ph(CONTENT_S, 1))
P_BUL_FIRST, P_BUL1, P_BUL2 = _bul[0], _bul[1], _bul[4]


def fill(shape, items):
    """items: list of (prototype <a:p>, text). Each paragraph keeps the prototype's formatting."""
    body = shape.text_frame._txBody
    for old in body.findall(qn("a:p")):
        body.remove(old)
    for proto, text in items:
        e = copy.deepcopy(proto)
        runs = e.findall(qn("a:r"))
        for extra in runs[1:] + e.findall(qn("a:br")) + e.findall(qn("a:fld")):
            e.remove(extra)
        if text and runs:
            runs[0].find(qn("a:t")).text = text
        elif runs:
            e.remove(runs[0])
        body.append(e)


def add_slide_number(slide):
    for sp in slide.slide_layout.placeholders:
        if sp.placeholder_format.type is not None and "SLIDE_NUMBER" in str(sp.placeholder_format.type):
            slide.shapes._spTree.append(copy.deepcopy(sp._element))


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


def new(layout_name, title, note):
    s = p.slides.add_slide(LAYOUT[layout_name])
    s.shapes.title.text = title
    add_slide_number(s)
    notes(s, note)
    return s


def section(title, sub, note):
    s = p.slides.add_slide(LAYOUT["Section"])
    s.shapes.title.text = title
    ph(s, 1).text = sub
    notes(s, note)
    return s


def bullets(title, items, note):
    s = new("Title and content", title, note)
    fill(ph(s, 1), [(P_BUL2 if it.startswith("  ") else P_BUL_FIRST if i == 0 else P_BUL1, it.strip())
                    for i, it in enumerate(items)])
    return s


def code(title, lines, note):
    """lines: (kind, text) with kind in 'c' comment, '' code, 'hl' highlighted."""
    s = new("Code", title, note)
    proto = {"c": P_CODE_COMMENT, "": P_CODE, "hl": P_CODE_HL}
    fill(ph(s, 1), [(proto[k], t) for k, t in lines])
    for r in ph(s, 1).text_frame._txBody.iter(qn("a:rPr")):
        for f in r.findall(qn("a:latin")):
            r.remove(f)
        r.insert(0, r.makeelement(qn("a:latin"), {"typeface": "Courier New"}))
    return s


# 1 Title (kept from the template)
notes(TITLE, "0:00. Skip the intro and go straight to the terminal. Paste Prompt 1 (demo-script.md) into "
      "Claude Code and press enter. Say: 'I'll come back to this in a few minutes. Let's see what it builds.'")

order = [TITLE]

# 2 Demo 1
order.append(section("Demo 1 · Build it", "A vinyl collection manager, built live by Claude Code",
    "Start Claude Code in the empty ~/demo/vinyl folder. Show this while the prompt runs. Prompt 1:\n"
    "Create an app that I can use to manage my vinyl collection. Add some Test data and a nice UI\n"
    "Point out that the prompt doesn't say Quarkus, Java, database or tests. The MCP server's instructions get "
    "it to Quarkus; from then on AGENTS.md and the skills decide what it builds (database, tests, Dev UI). If "
    "rehearsal showed you need ~/demo/CLAUDE.md to get Quarkus, show that one line: it's the first bit of teaching."))

# 3 Statement
s = STATEMENT_S
s.shapes.title.text = "AI agents already write Quarkus. Mostly last year’s Quarkus."
ph(s, 1).text = "Training data is a snapshot. Quarkus ships every month."
notes(s, "2:00. Let this sit for a second. Agents learned Quarkus from old data and don't know they're "
      "out of date. Glance at the terminal.")
order.append(s)

# 4 Two columns: the problem
s = new("Two columns", "Why agents get Quarkus wrong",
    "Left: knowledge. Use an example from rehearsal if you have one (javax.ws.rs imports, a REST client "
    "built by hand). Right: Dev MCP already exposes Dev UI to agents, but it runs inside the app, so it's "
    "gone at exactly the moment the agent needs it most: after a crash.")
fill(ph(s, 1), [(P_COL_HEAD, "What it knows"), (P_COL_BODY, "javax.*, RESTEasy Classic, old config keys"),
                (P_COL_BODY, "REST clients built by hand"), (P_COL_BODY, "Blog posts from Quarkus 1.x")])
fill(ph(s, 2), [(P_COL_HEAD, "What it can’t see"), (P_COL_BODY, "The running app and its test results"),
                (P_COL_BODY, "Dev Services, config, endpoints"), (P_COL_BODY, "The crash that just happened")])
order.append(s)

# 5 Architecture (the template's image-with-caption slide)
s = IMAGE_S
s.shapes.title.text = "Quarkus Agent MCP"
frame = next(sh for sh in s.shapes if sh.shape_type == 1)  # the image frame
drop = next(sh for sh in s.shapes if sh.has_text_frame and "Drop your image" in sh.text_frame.text)
fx, fy, fw, fh = frame.left, frame.top, frame.width, frame.height
for sh in (frame, drop):
    sh._element.getparent().remove(sh._element)
h = fh
w = int(h * 3200 / 2080)
if w > fw:
    w = fw
    h = int(w * 2080 / 3200)
s.shapes.add_picture(f"{repo}/architecture-diagram.png", fx + (fw - w) // 2, fy + (fh - h) // 2, w, h)
fill(_cap, [(P_CAP_HEAD, "Separate from your app"),
            (P_CAP_BODY, "It runs quarkus dev as a child process, so it stays up when the app crashes."),
            (P_CAP_BODY, ""),
            (P_CAP_BODY, "It proxies the app’s Dev MCP tools and adds skills and doc search.")])
notes(s, "A standalone MCP server. Works in Claude Code, VS Code/Copilot, Cursor, Windsurf, JetBrains, "
      "IBM Bob and ACP agents.")
order.append(s)

# 6 Tools
order.append(bullets("What the agent gets", [
    "Create and run: quarkus_create, start, attach, browser",
    "Skills: how to use each extension, from its authors",
    "Docs: semantic search, matched to your Quarkus version",
    "Your running app: tests, config, endpoints, exceptions",
    "Upgrades: quarkus_update, with a dry run"],
    "Don't read the list aloud. Pick one example from each line. The running-app tools come from Dev MCP "
    "(quarkus_searchTools / quarkus_callTool) and change as you add extensions. Glance at the terminal: it "
    "has probably called quarkus_create and quarkus_skills by now."))

# 7 Skills
order.append(bullets("Skills before code", [
    "Each extension ships a skill: patterns, tests, pitfalls",
    "  “Use @Transactional for writes with Panache”",
    "Layered, and the most specific wins:",
    "  extension JAR → bundled → ~/.quarkus/skills → .agent/skills",
    "Every new project also gets an AGENTS.md"],
    "This is the 'teaching' in the title. ~/.quarkus/skills (yours) and .agent/skills (your project's, "
    "commit it) are where you teach it your team's rules; that's Demo 3. ~9:00: go back to the terminal."))

# 8 Back to the demo
order.append(section("Back to the demo", "What did it build, and how did it get there?",
    "Scroll the transcript and point at: quarkus_create (scaffold + auto start), AGENTS.md, quarkus_skills, "
    "quarkus_searchDocs, then tests via quarkus_callTool → devui-testing_runTests. Ask it to open the app and "
    "the Dev UI (quarkus_browser). Add a record in the UI (or with curl) and show it in the list. If it's stuck after 60 s, "
    "switch to the finished branch or the recording."))

# 9 Break it
order.append(section("Demo 2 · Break it", "The app crashes. The agent keeps going.",
    "17:00. Prompt 2: Add a listening queue: show the record that's playing now and what's up next.\n"
    "Apply your prepared bad edit (picked in rehearsal for the stack it usually chooses, e.g. remove "
    "@Transactional or break an entity field). The app fails but "
    "the MCP server stays up. Point at devui-exceptions_getLastException: exception class, message and the "
    "line in your code. The agent fixes it and reruns the tests."))

# 10 Teach it
order.append(section("Demo 3 · Teach it", "Your team’s rules, as a skill",
    "22:00. Prompt 3: Our team rule: REST errors must use RFC 7807 Problem Details, and resources return "
    "Java records, never entities. Remember this as a skill for quarkus-rest.\n"
    "Show the quarkus_updateSkill call, then the next slide.\n"
    "Start with an empty ~/.quarkus/skills!"))

# 11 What a skill looks like
order.append(code("A skill is just Markdown", [
    ("c", "# ~/.quarkus/skills/quarkus-rest/SKILL.md"),
    ("", "---"),
    ("", "name: quarkus-rest"),
    ("", "description: Our team's REST conventions"),
    ("hl", "mode: enhance"),
    ("", "---"),
    ("", "- Resources return Java records, never entities"),
    ("", "- Errors use RFC 7807 Problem Details")],
    "Illustrative: show the real file the agent just wrote. 'enhance' appends to the extension's own skill "
    "instead of replacing it.\nPrompt 4: Let me lend records to friends. A record that's already lent out can't be lent "
    "again, and I want to see who has what. Point out the Java records in the responses (not entities) and "
    "Problem Details for the already-lent error, without being told again. Then "
    "quarkus_saveSkill → .agent/skills/: 'commit this and your whole team's agent gets the rule'."))

# 12 Extension authors
order.append(bullets("For extension authors", [
    "Ship META-INF/quarkus-skill.md in your deployment JAR",
    "Write it by watching an agent use your extension",
    "  Every time you had to help it, that's a line in the skill",
    "Ship doc embeddings so quarkus_searchDocs finds your docs",
    "Dev UI tools you add show up for agents automatically"],
    "30:00. Works for core, Quarkiverse and your own internal extensions. docs/creating-extension-skills.md "
    "describes the loop: a Worker session (Opus) and a Tester session (Sonnet). Run a realistic prompt, note "
    "every intervention, turn them into the skill, and test again."))

# 13 Workflow
order.append(bullets("Fits how you already work", [
    "App already running in your terminal? quarkus_attach",
    "Upgrading? quarkus_update with dryRun=true first",
    "Claude Code, VS Code, Cursor, Windsurf, JetBrains, Bob, ACP",
    "JVM via JBang, or a native binary for Linux and macOS"],
    "Optional quick show: start a Quarkus app by hand in a second terminal and ask the agent to attach to it. "
    "Skip it if you're behind time."))

# 14 Try it (the closing slide's QR code already points to the repo)
order.append(code("Try it", [
    ("c", "# Claude Code"),
    ("", "claude plugin marketplace add quarkusio/quarkus-agent-mcp"),
    ("hl", "claude plugin install quarkus-agent@quarkus-tools"),
    ("", ""),
    ("c", "# Any other MCP client: run the server with JBang"),
    ("hl", "jbang quarkus-agent-mcp@quarkusio"),
    ("", ""),
    ("c", "# github.com/quarkusio/quarkus-agent-mcp")],
    "34:00. Part of the DevStar working group: github.com/quarkusio/quarkus/discussions/53093. "
    "Then go to the closing slide (QR code to the repo) and leave it up during Q&A."))

# 15 Closing (kept from the template)
notes(CLOSING, "36:00. Keep about 4 minutes for questions.")
order.append(CLOSING)

# Reorder, and drop the template's sample slides
lst = p.slides._sldIdLst
pairs = [(sl.slide_id, el) for sl, el in zip(p.slides, list(lst))]
ids = dict(pairs)
keep = {sl.slide_id for sl in order}
order_ids = [sl.slide_id for sl in order]
for sid, el in pairs:
    if sid not in keep:
        p.part.drop_rel(el.rId)
for el in list(lst):
    lst.remove(el)
for sid in order_ids:
    lst.append(ids[sid])
p.save(out)
