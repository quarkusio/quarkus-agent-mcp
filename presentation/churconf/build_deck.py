import os, sys, tempfile, qrcode
from pptx import Presentation
from pptx.util import Inches, Pt
repo, out = sys.argv[1], sys.argv[2]
p = Presentation()
p.slide_width, p.slide_height = Inches(13.333), Inches(7.5)
TITLE, CONTENT, SECTION, TITLE_ONLY = 0, 1, 2, 5
k = 13.333 / 10
for shp_owner in [p.slide_master, *p.slide_layouts]:
    for sh in shp_owner.placeholders:
        if sh.left is not None:
            sh.left, sh.width = int(sh.left * k), int(sh.width * k)
qr = qrcode.make("https://github.com/quarkusio/quarkus-agent-mcp"); qr_path = os.path.join(tempfile.mkdtemp(), "qr.png"); qr.save(qr_path)

def place_title(s):
    t = s.shapes.title
    t.left, t.top, t.width, t.height = Inches(0.7), Inches(0.35), Inches(11.9), Inches(1.1)

def notes(s, text): s.notes_slide.notes_text_frame.text = text

def bullets(s, items, size=24):
    tf = s.placeholders[1].text_frame; tf.clear()
    first = True
    for it in items:
        lvl = 1 if it.startswith("  ") else 0
        para = tf.paragraphs[0] if first else tf.add_paragraph(); first = False
        para.text = it.strip(); para.level = lvl
        para.font.size = Pt(size - 4*lvl)

def content(title, items, note, size=24, width=None):
    s = p.slides.add_slide(p.slide_layouts[CONTENT]); s.shapes.title.text = title; place_title(s)
    ph = s.placeholders[1]
    ph.left, ph.top, ph.width, ph.height = Inches(0.7), Inches(1.6), width or Inches(11.9), Inches(5.4)
    bullets(s, items, size); notes(s, note); return s

def section(title, sub, note):
    s = p.slides.add_slide(p.slide_layouts[SECTION]); s.shapes.title.text = title
    s.placeholders[1].text = sub; notes(s, note); return s

# 1 Title
s = p.slides.add_slide(p.slide_layouts[TITLE])
s.shapes.title.text = "Teaching AI Agents to Speak Quarkus"
s.placeholders[1].text = "Phillip Kruger · Quarkus, Red Hat\nChurConf Sydney · 18 November 2026"
notes(s, "0:00. Skip the intro and go straight to the terminal. "
  "Paste Prompt 1 (Demo 1 in demo-script.md) into Claude Code and press enter. "
  "Say: 'I'll come back to this in a few minutes. Let's see what it builds.'")

# 2 Demo 1
section("Demo 1 · Build it", "Building a ChurConf agenda app with Claude Code + Quarkus Agent MCP",
  "Show this while the prompt runs. Prompt 1:\n"
  "Create a Quarkus app in ~/demo/churconf for a conference agenda. Talks have title, speaker, track, "
  "room, start time and duration. REST API to list talks, filter by day and track, and mark favourites. "
  "Use PostgreSQL. Seed it with 6 talks from ChurConf Sydney 2026. Add a simple web page listing the "
  "agenda. Write tests and make sure they pass.")

# 3 problem: knowledge
content("Agents learned Quarkus from the internet",
  ["Training data is a snapshot; Quarkus ships a release every month",
   "So you get javax.*, RESTEasy Classic, old config keys",
   "Hand-built REST clients instead of CDI injection",
   "Blog-post patterns from Quarkus 1.x",
   "The agent writes it confidently either way"],
  "2:00. Agents learned Quarkus from old data and don't know they're out of date. "
  "Give a concrete example if you have one from rehearsal (javax.ws.rs imports, @RestClient built by hand). "
  "Glance at the terminal.")

# 4 problem: blind
content("…and it can't see your app",
  ["It can't see the running app, test results, Dev Services or config",
   "Its only feedback is the compiler and the logs it happens to read",
   "When the app crashes, the agent's feedback loop goes with it",
   "Quarkus already has this information: Dev UI and Dev MCP",
   "  But Dev MCP lives inside the app, so it goes down with it"],
  "Dev MCP (in Quarkus since 3.2x) exposes Dev UI to agents, but it runs inside the app process, "
  "so it's gone at exactly the moment the agent needs it most: after a crash.")

# 5 architecture
s = p.slides.add_slide(p.slide_layouts[TITLE_ONLY]); s.shapes.title.text = "Quarkus Agent MCP"; place_title(s)
img = f"{repo}/architecture-diagram.png"
h = Inches(5.7); w = int(h * 3200 / 2080)
s.shapes.add_picture(img, int((p.slide_width - w) / 2), Inches(1.6), height=h)
notes(s, "A standalone MCP server, separate from the app. It wraps `quarkus dev` as a child process, "
  "so it stays up when the app crashes. It proxies the app's Dev MCP tools and adds skills and doc search. "
  "Works in Claude Code, VS Code/Copilot, Cursor, Windsurf, JetBrains, IBM Bob, and ACP agents.")

# 6 tools
content("What the agent gets",
  ["Create & run: quarkus_create · start · attach · stop · restart · logs · browser",
   "Skills: quarkus_skills · updateSkill · saveSkill",
   "  How to use each extension correctly, from the extension authors",
   "Docs: quarkus_searchDocs",
   "  Semantic search, matched to your project's Quarkus version",
   "Your running app: quarkus_searchTools · quarkus_callTool",
   "  Tests, config, endpoints, Dev Services, exceptions…",
   "Upgrade: quarkus_update (OpenRewrite, with dry run)"],
  "Don't read the list aloud. Pick one line from each group. The Dev MCP tool list changes as you add extensions. "
  "Glance at the terminal: it has probably called quarkus_create and quarkus_skills by now.", size=22)

# 7 skills
content("Skills before code",
  ["Each extension ships a skill: patterns, testing, pitfalls",
   "  \"Use @Transactional for write operations with Panache\"",
   "  \"Don't build REST clients by hand; let CDI inject them\"",
   "Layered; the most specific wins:",
   "  1. Extension JAR (META-INF/quarkus-skill.md)",
   "  2. Bundled community skills (migration, updates)",
   "  3. Yours: ~/.quarkus/skills",
   "  4. Your project's: .agent/skills (commit it)",
   "Plus AGENTS.md in every new project"],
  "This is the 'teaching' part of the title. Layers 3 and 4 are where you teach it your team's rules; that's Demo 3. "
  "~9:00: wrap up and go back to the terminal.", size=22)

# 8 back to demo
section("Back to the demo", "What did it build, and how did it get there?",
  "Scroll the transcript and point at: quarkus_create (scaffold + auto start), AGENTS.md, quarkus_skills, "
  "quarkus_searchDocs, then tests via quarkus_callTool → devui-testing_runTests. "
  "Ask it to open the app and the Dev UI (quarkus_browser). Add a talk with curl and show it in the page. "
  "If it's still running, narrate live. If it's stuck after 60 s, switch to the finished branch / recording.")

# 9 break it
section("Demo 2 · Break it", "The app crashes, but the agent keeps going",
  "17:00. Prompt 2: Add an endpoint that returns 'what's on now' and 'up next' for each room.\n"
  "Before or during this, apply your prepared bad edit (e.g. remove @Transactional or break an entity field). "
  "The app fails but the MCP server stays up. Point at devui-exceptions_getLastException: "
  "it returns the exception class, message, and the line in your code. The agent fixes it and reruns the tests.")

# 10 teach it
section("Demo 3 · Teach it", "Your team's rules, as a skill",
  "22:00. Prompt 3: Our team rule: REST errors must use RFC 7807 Problem Details, and resources return Java records, "
  "never entities. Remember this as a skill for quarkus-rest.\n"
  "Show the quarkus_updateSkill call and the file in ~/.quarkus/skills/ (mode: enhance appends to the extension's skill).\n"
  "Prompt 4: Add a POST endpoint to submit talk feedback with a 1–5 rating, rejecting invalid input.\n"
  "Point out: records and Problem Details, without being told again. Then quarkus_saveSkill → .agent/skills/, "
  "'commit this and your whole team's agent gets the rule'. Start with an empty ~/.quarkus/skills!")

# 11 extension authors
content("For extension authors",
  ["Ship META-INF/quarkus-skill.md in your deployment JAR",
   "  Core, Quarkiverse, and your own internal extensions",
   "How to write one: watch an agent use your extension",
   "  Note where it gets stuck, then write that down as the skill",
   "Ship doc embeddings so quarkus_searchDocs finds your docs",
   "Dev UI / Dev MCP tools you add show up for agents automatically"],
  "30:00. docs/creating-extension-skills.md describes the loop: two Claude sessions, a Worker (Opus) and a Tester (Sonnet). "
  "Run a realistic prompt, note every intervention, turn them into the skill, and test again.")

# 12 fits your workflow
content("Fits how you already work",
  ["App already running in your terminal? quarkus_attach",
   "Upgrading? quarkus_update with dryRun=true first",
   "Any MCP client: Claude Code, VS Code / Copilot, Cursor,",
   "  Windsurf, JetBrains, IBM Bob, ACP agents",
   "JVM via JBang, or a native binary for Linux & macOS"],
  "Optional quick show: start a Quarkus app by hand in a second terminal and ask the agent to attach to it. "
  "Skip it if you're behind time.")

# 13 get it
s = content("Try it",
  ["Claude Code:",
   "  claude plugin marketplace add quarkusio/quarkus-agent-mcp",
   "  claude plugin install quarkus-agent@quarkus-tools",
   "Anything else:",
   "  jbang quarkus-agent-mcp@quarkusio",
   "github.com/quarkusio/quarkus-agent-mcp"],
  "34:00. Leave this slide up during Q&A. Part of the DevStar working group: "
  "github.com/quarkusio/quarkus/discussions/53093", size=22, width=Inches(8.4))
s.shapes.add_picture(qr_path, Inches(9.4), Inches(2.0), height=Inches(3.4))

# 14 thanks
s = p.slides.add_slide(p.slide_layouts[TITLE])
s.shapes.title.text = "Thank you · Questions?"
s.placeholders[1].text = "github.com/quarkusio/quarkus-agent-mcp"
notes(s, "36:00. Keep about 4 minutes for questions.")
p.save(out)
