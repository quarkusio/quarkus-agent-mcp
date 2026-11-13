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
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

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


# Template colours (theme of the ChurConf master)
NAVY, PURPLE, ORANGE, AMBER = "241440", "7C297D", "DD552C", "F79A23"
CREAM, LILAC, GREY, WHITE = "F6EEE9", "E9D8F0", "8A7A99", "FFFFFF"


def box(slide, x, y, w, h, text, fill=CREAM, fg=NAVY, line=None, size=12, bold=False, dashed=False,
        shape=MSO_SHAPE.ROUNDED_RECTANGLE):
    """A diagram box; x, y, w, h in inches."""
    sh = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if fill:
        sh.fill.solid()
        sh.fill.fore_color.rgb = RGBColor.from_string(fill)
    else:
        sh.fill.background()
    sh.line.color.rgb = RGBColor.from_string(line or fill or GREY)
    sh.line.width = Pt(1.25)
    if dashed:
        sh.line.dash_style = 4  # dash
    sh.shadow.inherit = False
    tf = sh.text_frame
    tf.word_wrap = True
    for side in ("margin_left", "margin_right", "margin_top", "margin_bottom"):
        setattr(tf, side, Inches(0.05))
    for i, line_text in enumerate(text.split("\n")):
        para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        para.alignment = PP_ALIGN.CENTER
        r = para.add_run()
        r.text = line_text
        r.font.size = Pt(size if i == 0 else size - 2)
        r.font.bold = bold if i == 0 else False
        r.font.color.rgb = RGBColor.from_string(fg)
        r.font.name = "Montserrat"
    return sh


def arrow(slide, x1, y1, x2, y2, color=GREY, dashed=False):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    c.line.color.rgb = RGBColor.from_string(color)
    c.line.width = Pt(1.5)
    if dashed:
        c.line.dash_style = 4
    ln = c.line._get_or_add_ln()
    ln.append(ln.makeelement(qn("a:tailEnd"), {"type": "triangle", "w": "med", "len": "med"}))
    return c


def label(slide, x, y, w, h, text, fg=GREY, size=11, bold=False, align=PP_ALIGN.LEFT):
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = True
    para = tf.paragraphs[0]
    para.alignment = align
    r = para.add_run()
    r.text = text
    r.font.size = Pt(size)
    r.font.bold = bold
    r.font.color.rgb = RGBColor.from_string(fg)
    r.font.name = "Montserrat"
    return tb


def code(title, lines, note, size=None):
    """lines: (kind, text) with kind in 'c' comment, '' code, 'hl' highlighted."""
    s = new("Code", title, note)
    proto = {"c": P_CODE_COMMENT, "": P_CODE, "hl": P_CODE_HL}
    fill(ph(s, 1), [(proto[k], t) for k, t in lines])
    for r in ph(s, 1).text_frame._txBody.iter(qn("a:rPr")):
        for f in r.findall(qn("a:latin")):
            r.remove(f)
        r.insert(0, r.makeelement(qn("a:latin"), {"typeface": "Courier New"}))
        if size:
            r.set("sz", str(size * 100))
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

# 4 How we got here (history, drawn)
s = new("Title only", "How we got here", "")
box(s, 0.5, 1.45, 1.35, 0.6, "Developer", fill=PURPLE, fg=WHITE, bold=True)
box(s, 2.45, 1.45, 1.45, 0.6, "Dev UI\nin the browser", fill=LILAC)
box(s, 4.6, 1.35, 2.3, 0.55, "Dev UI service")
box(s, 4.6, 2.3, 2.3, 0.65, "Chappie\nGen AI + RAG")
box(s, 7.6, 2.35, 1.4, 0.55, "LLM", fill=None, line=GREY, fg=NAVY)
box(s, 4.6, 3.55, 2.3, 0.6, "Dev MCP\ninside your app", fill=AMBER, fg=NAVY, line=AMBER, bold=True)
box(s, 0.5, 3.55, 1.35, 0.6, "AI agent", fill=ORANGE, fg=WHITE, bold=True)
arrow(s, 1.85, 1.75, 2.45, 1.75)
arrow(s, 3.9, 1.7, 4.6, 1.62)
arrow(s, 3.9, 1.85, 4.6, 2.55)
arrow(s, 3.9, 2.0, 4.6, 3.7)
arrow(s, 6.9, 2.62, 7.6, 2.62)
arrow(s, 5.75, 2.95, 5.75, 3.55)
arrow(s, 1.85, 3.85, 4.6, 3.85, color=ORANGE)
label(s, 0.5, 2.15, 1.9, 0.35, "Dev Ex", fg=PURPLE, size=14, bold=True)
label(s, 0.5, 4.25, 1.9, 0.35, "AI Ex", fg=ORANGE, size=14, bold=True)
notes(s, "~2:30. Borrowed from 'Coding Agents with Quarkus'. Tell it as a build-up, top to bottom: first Dev UI, "
      "a UI for the developer in the browser. Then Chappie: Gen AI + RAG behind Dev UI. Then Dev MCP: the same "
      "Dev UI services, now exposed over MCP, so an agent can call them too. Then take a step back: everything "
      "here was designed for Dev Ex, the human in the browser. The agent is a different user, with a different "
      "experience: AI Ex.")
order.append(s)

# 5 Through the eyes of the agent
s = new("Two columns", "Through the eyes of the agent",
    "~3:30. Left: what it knows from training. Use an example from rehearsal if you have one (javax.ws.rs imports, "
    "a REST client built by hand). Right: Dev MCP's shortcomings for an agent. It runs inside the app, so there's "
    "nothing to talk to before the app exists, and it's gone at exactly the moment the agent needs it most: after a "
    "crash. Too many tools fill the agent's context. And the tools were designed for Dev Ex: continuous testing "
    "reruns tests in the background for a human watching the console; an agent needs 'run the tests and tell me "
    "the result'.")
fill(ph(s, 1), [(P_COL_HEAD, "What it knows"), (P_COL_BODY, "javax.*, RESTEasy Classic, old config keys"),
                (P_COL_BODY, "REST clients built by hand"), (P_COL_BODY, "Blog posts from Quarkus 1.x")])
fill(ph(s, 2), [(P_COL_HEAD, "What Dev MCP gives it"),
                (P_COL_BODY, "Runs inside the app: can’t create one, and gone when it crashes"),
                (P_COL_BODY, "Too many tools"),
                (P_COL_BODY, "Tools built for Dev Ex, not AI Ex (continuous testing)")])
order.append(s)

# 6 Quarkus Agent MCP (the template's image-with-caption slide, with a drawn diagram)
s = IMAGE_S
s.shapes.title.text = "Quarkus Agent MCP"
frame = next(sh for sh in s.shapes if sh.shape_type == 1)  # the image frame
drop = next(sh for sh in s.shapes if sh.has_text_frame and "Drop your image" in sh.text_frame.text)
for sh in (frame, drop):
    sh._element.getparent().remove(sh._element)
box(s, 0.5, 2.75, 1.15, 0.7, "AI agent", fill=ORANGE, fg=WHITE, bold=True)
box(s, 2.1, 2.45, 1.85, 1.3, "Quarkus Agent MCP\nalways running", fill=PURPLE, fg=WHITE, bold=True)
box(s, 2.4, 1.6, 1.25, 0.5, "Skills", fill=LILAC, shape=MSO_SHAPE.CAN)
box(s, 2.4, 4.1, 1.25, 0.5, "Docs (RAG)", fill=LILAC, shape=MSO_SHAPE.CAN)
box(s, 4.3, 2.2, 1.65, 1.8, "", fill=None, line=GREY, dashed=True)
label(s, 4.3, 3.6, 1.65, 0.35, "your app, quarkus dev", size=9, align=PP_ALIGN.CENTER)
box(s, 4.45, 2.75, 1.35, 0.7, "Dev MCP", fill=AMBER, line=AMBER, bold=True)
arrow(s, 1.65, 3.1, 2.1, 3.1, color=ORANGE)
arrow(s, 3.95, 3.1, 4.45, 3.1)
arrow(s, 3.02, 2.45, 3.02, 2.1)
arrow(s, 3.02, 3.75, 3.02, 4.1)
fill(_cap, [(P_CAP_HEAD, "Built for AI Ex"),
            (P_CAP_BODY, "A standalone MCP server: there before the app exists, and after it crashes."),
            (P_CAP_BODY, ""),
            (P_CAP_BODY, "Full lifecycle: create, start, update. Proxies Dev MCP, and adds docs and skills.")])
notes(s, "~4:30. It runs quarkus dev as a child process, so it stays up when the app crashes. Dev MCP tools are "
      "found with quarkus_searchTools and called with quarkus_callTool, so they don't flood the agent's context. "
      "Works in Claude Code, VS Code/Copilot, Cursor, Windsurf, JetBrains, IBM Bob and ACP agents.")
order.append(s)

# 7 Tools, in the groups from 'Coding Agents with Quarkus'
order.append(bullets("What the agent gets", [
    "Lifecycle: create, start, attach, stop, status, browser",
    "Knowledge: quarkus_skills and quarkus_searchDocs",
    "Dev MCP: quarkus_searchTools and quarkus_callTool",
    "Skill customisation: quarkus_updateSkill and saveSkill",
    "Logs and upgrades: app and agent logs, quarkus_update"],
    "~5:30. Don't read the list aloud. Pick one example from each line. The Dev MCP tool list is dynamic: it "
    "changes as you add extensions. Glance at the terminal: it has probably called quarkus_create and "
    "quarkus_skills by now."))

# 8 Skills
order.append(bullets("Skills before code", [
    "Each extension ships a skill: patterns, tests, pitfalls",
    "  “Use @Transactional for writes with Panache”",
    "Layered, and the most specific wins:",
    "  extension JAR → bundled → ~/.quarkus/skills → .agent/skills",
    "Every new project also gets an AGENTS.md"],
    "This is the 'teaching' in the title. ~/.quarkus/skills (yours) and .agent/skills (your project's, "
    "commit it) are where you teach it your team's rules; that's Demo 3. ~9:00: go back to the terminal."))

# 9 Back to the demo
order.append(section("Back to the demo", "What did it build, and how did it get there?",
    "Scroll the transcript and point at: quarkus_create (scaffold + auto start), AGENTS.md, quarkus_skills, "
    "quarkus_searchDocs, then tests via quarkus_callTool → devui-testing_runTests. Ask it to open the app and "
    "the Dev UI (quarkus_browser). Add a record in the UI (or with curl) and show it in the list. If it's stuck after 60 s, "
    "switch to the finished branch or the recording."))

# 10 Break it
order.append(section("Demo 2 · Break it", "The app crashes. The agent keeps going.",
    "17:00. Prompt 2: Add a listening queue: show the record that's playing now and what's up next.\n"
    "Apply your prepared bad edit (picked in rehearsal for the stack it usually chooses, e.g. remove "
    "@Transactional or break an entity field). The app fails but "
    "the MCP server stays up. Point at devui-exceptions_getLastException: exception class, message and the "
    "line in your code. The agent fixes it and reruns the tests."))

# 11 Teach it
order.append(section("Demo 3 · Teach it", "Your team’s rules, as a skill",
    "22:00. Prompt 3: Our team rule: REST errors must use RFC 7807 Problem Details, and resources return "
    "Java records, never entities. Remember this as a skill for quarkus-rest.\n"
    "Show the quarkus_updateSkill call, then the next slide.\n"
    "Start with an empty ~/.quarkus/skills!"))

# 12 What a skill looks like
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

# 13 Extension authors: what you ship (recap diagram from 'Coding Agents with Quarkus')
s = new("Title only", "For extension authors",
    "30:00. Three things in your extension, and the release turns them into what agents use. The skill gets "
    "your extension's metadata (name, description, guide link) and its Dev MCP tools added. Your AsciiDoc guide "
    "becomes an exported vector DB (docs.sql) that quarkus_searchDocs loads. Your Dev MCP tools are found with "
    "quarkus_searchTools and called with quarkus_callTool. Works for core, Quarkiverse and your own internal "
    "extensions.")
box(s, 0.45, 1.55, 1.55, 2.85, "", fill="DCE3F5", shape=MSO_SHAPE.RECTANGLE)
label(s, 0.5, 2.8, 1.05, 0.4, "Source", fg=NAVY, size=12, bold=True)
box(s, 4.15, 1.55, 1.55, 2.85, "", fill="DCE3F5", shape=MSO_SHAPE.RECTANGLE)
label(s, 4.2, 2.8, 1.05, 0.4, "Released", fg=NAVY, size=12, bold=True)
for i, (src, dst, why) in enumerate([("quarkus-skill.md", "skill.md", "+ metadata\n+ Dev MCP tools"),
                                     ("docs.adoc", "docs.sql", "exported\nvector DB"),
                                     ("Dev MCP tools", "Dev MCP tools", "searchTools\n+ callTool")]):
    y = 1.75 + i * 0.9
    box(s, 1.6, y, 1.55, 0.6, src, fill="DDEFD8", shape=MSO_SHAPE.RECTANGLE, size=11)
    box(s, 5.3, y, 1.55, 0.6, dst, fill=LILAC, shape=MSO_SHAPE.RECTANGLE, size=11)
    box(s, 7.5, y - 0.02, 1.9, 0.64, why, fill=AMBER, line=AMBER, size=11, bold=True)
    arrow(s, 6.85, y + 0.3, 7.5, y + 0.3)
arrow(s, 3.3, 2.95, 4.05, 2.95, color=ORANGE)
label(s, 3.15, 2.5, 1.0, 0.35, "release", fg=ORANGE, size=13, bold=True, align=PP_ALIGN.CENTER)
order.append(s)

# 14 Extension authors: the details
order.append(code("Shipping skills and docs", [
    ("c", "# Your skill: patterns, tests, pitfalls"),
    ("hl", "deployment/src/main/resources/META-INF/quarkus-skill.md"),
    ("", ""),
    ("c", "# Your docs: core extensions get this for free"),
    ("c", "# Quarkiverse: in the deployment pom.xml"),
    ("hl", "<quarkus-rag.guide>docs/modules/ROOT/pages/index.adoc"),
    ("hl", "</quarkus-rag.guide>"),
    ("", ""),
    ("c", "# Dev MCP tools: write them for an agent, not a person")],
    "~32:00. Write the skill by watching an agent use your extension: every time you had to help it, that's a "
    "line in the skill (docs/creating-extension-skills.md: a Worker session and a Tester session). For docs, see "
    "docs/contributing-extension-docs.md and github.com/quarkusio/quarkus-documentation-rag; check before the "
    "talk whether quarkiverse-parent#235 has been merged, since the Quarkiverse property depends on it.", size=12))

# 15 Workflow
order.append(bullets("Fits how you already work", [
    "App already running in your terminal? quarkus_attach",
    "Upgrading? quarkus_update with dryRun=true first",
    "Claude Code, VS Code, Cursor, Windsurf, JetBrains, Bob, ACP",
    "JVM via JBang, or a native binary for Linux and macOS"],
    "Optional quick show: start a Quarkus app by hand in a second terminal and ask the agent to attach to it. "
    "Skip it if you're behind time."))

# 16 Try it (the closing slide's QR code already points to the repo)
order.append(code("Try it", [
    ("c", "# Claude Code"),
    ("", "claude plugin marketplace add quarkusio/quarkus-agent-mcp"),
    ("hl", "claude plugin install quarkus-agent@quarkus-tools"),
    ("", ""),
    ("c", "# Any other agent: add this MCP server"),
    ("", "\"quarkus-agent\": {"),
    ("hl", "  \"command\": \"jbang\", \"args\": [\"quarkus-agent-mcp@quarkusio\"]"),
    ("", "}")],
    "34:00. The plugin and JBang need a JVM; the next slide is the native binary. Part of the DevStar working "
    "group: github.com/quarkusio/quarkus/discussions/53093.", size=13))

# 17 Native binary, as in the README's "Native binary (Linux & macOS)" section
order.append(code("No JVM? Native binary for Linux and macOS", [
    ("c", "# Quick install: detects your platform, downloads to ~/.local/bin"),
    ("c", "# and registers it with Claude Code. Run it again to update."),
    ("hl", "curl -sL https://raw.githubusercontent.com/quarkusio/\\"),
    ("hl", "quarkus-agent-mcp/main/install.sh | bash"),
    ("", ""),
    ("c", "# Or download it from the latest GitHub release yourself:"),
    ("c", "#   quarkus-agent-mcp-<version>-linux-x86_64"),
    ("c", "#   quarkus-agent-mcp-<version>-macos-aarch64 / -macos-x86_64"),
    ("", "claude mcp add -s user quarkus-agent -- ~/.local/bin/quarkus-agent-mcp")],
    "~35:00. Starts instantly and uses much less memory than the JVM version, which helps on a VPS or a small "
    "machine. No JVM or JBang needed. Windows isn't supported for the native binary: use JBang there. The URL "
    "is split over two lines only to fit the slide; it's one command (and it's in the README). Then go to the "
    "closing slide (QR code to the repo) and leave it up during Q&A.", size=12))

# 18 Closing (kept from the template)
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
