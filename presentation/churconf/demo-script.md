# Teaching AI Agents to Speak Quarkus: ChurConf Sydney, 18 Nov 2026 (10:15–10:55)

Slides: `teaching-ai-agents-to-speak-quarkus.pptx`. Speaker notes on every slide give the timing and what to point at.
To use the template, open the template deck in Google Slides, choose **File → Import slides**, pick this .pptx, select all slides and
**untick "Keep original theme"**. The slides use only standard layouts (Title, Title and body, Section header, Title only), so they take on the template's layouts.

To regenerate after edits: `python -m venv v && v/bin/pip install python-pptx qrcode pillow && v/bin/python build_deck.py <repo root> out.pptx`

## Run sheet

| Time | Slide | What happens |
|---|---|---|
| 0:00 | 1–2 | Paste Prompt 1, press enter, show the "Demo 1" slide |
| 2:00 | 3–7 | Why agents get Quarkus wrong · architecture · tools · skills. Glance at the terminal |
| 9:00 | 8 | Back to the terminal: walk through the transcript, `quarkus_browser`, curl |
| 17:00 | 9 | Prompt 2 plus your prepared bad edit; the agent recovers using `devui-exceptions_getLastException` |
| 22:00 | 10 | Prompts 3 and 4: teach a rule with `quarkus_updateSkill`, then show `quarkus_saveSkill` |
| 30:00 | 11–12 | Extension authors · optional `quarkus_attach` demo |
| 34:00 | 13–14 | Install + QR, then Q&A |

## Prompts

1. Create a Quarkus app in ~/demo/churconf for a conference agenda. Talks have title, speaker, track, room, start time and duration. REST API to list talks, filter by day and track, and mark favourites. Use PostgreSQL. Seed it with 6 talks from ChurConf Sydney 2026. Add a simple web page listing the agenda. Write tests and make sure they pass.
2. Add an endpoint that returns "what's on now" and "up next" for each room.
3. Our team rule: REST errors must use RFC 7807 Problem Details, and resources return Java records, never entities. Remember this as a skill for quarkus-rest.
4. Add a POST endpoint to submit talk feedback with a 1–5 rating, rejecting invalid input.

## Checklist (day before and morning of)

- [ ] Run the full demo end to end 3–4 times; note which tool calls usually show up
- [ ] Warm caches: `~/.m2` (run Prompt 1 once in another directory), JBang, `docker pull postgres` and the doc-search image
- [ ] Call `quarkus_searchDocs` once before going on stage (the first container start is slow)
- [ ] Empty `~/.quarkus/skills/` and delete `~/demo/churconf`
- [ ] Pre-allow `mcp__quarkus-agent__*`, edits and bash in the demo directory, so no permission prompts appear on stage
- [ ] Prepared bad edit for Demo 2, tested to fail the way you expect
- [ ] Fallbacks: a finished app on a git branch and a screen recording of each demo; switch if stuck for more than 60 s
- [ ] Large terminal font, notifications off, phone hotspot ready
