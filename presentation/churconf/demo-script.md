# Teaching AI Agents to Speak Quarkus: ChurConf Sydney, 18 Nov 2026 (10:15–10:55)

Slides: `teaching-ai-agents-to-speak-quarkus.pptx`, built on the ChurConf / Community Over Code Sydney template.
Speaker notes on every slide give the timing, what to point at, and the prompts.

To regenerate after editing `build_deck.py` (the template is not in the repo; pass your copy):

    python -m venv v && v/bin/pip install python-pptx
    v/bin/python build_deck.py "Teaching AI Agents to Speak Quarkus.pptx" <repo root> teaching-ai-agents-to-speak-quarkus.pptx

## Run sheet

| Time | Slide | What happens |
|---|---|---|
| 0:00 | 1–2 | Paste Prompt 1, press enter, show the "Demo 1" slide |
| 2:00 | 3–7 | Statement · why agents get Quarkus wrong · architecture · tools · skills. Glance at the terminal |
| 9:00 | 8 | Back to the terminal: walk through the transcript, `quarkus_browser`, add a departure with curl |
| 17:00 | 9 | Prompt 2 plus your prepared bad edit; the agent recovers using `devui-exceptions_getLastException` |
| 22:00 | 10–11 | Prompts 3 and 4: teach a rule with `quarkus_updateSkill`, show the skill file, then `quarkus_saveSkill` |
| 30:00 | 12–13 | Extension authors · optional `quarkus_attach` demo |
| 34:00 | 14–15 | Install commands, then the closing slide (QR code to the repo) during Q&A |

## Prompts

1. Create a Quarkus app in ~/demo/ferries for a Sydney ferry departures board. A departure has a route (like F1 Manly), wharf, destination, departure time and trip duration. REST API to list departures, filter by wharf and route, and mark departures as favourites. Use PostgreSQL. Seed it with a timetable from Circular Quay wharves 2 to 6, with departures every 10 to 20 minutes from 6am to 10pm (make the times up). Add a simple web page that shows it as a departures board. Write tests and make sure they pass.
2. Add an endpoint that returns the next departure and the one after it from each wharf, from now or from an optional time parameter.
3. Our team rule: REST errors must use RFC 7807 Problem Details, and resources return Java records, never entities. Remember this as a skill for quarkus-rest.
4. Add a POST endpoint to rate a departure from 1 to 5, rejecting invalid input.

## Other demo apps

The ferry board above is the default and matches the slides. Every option below has the same shape, so the run sheet,
the bad edit and Prompt 3 (the team rule) stay the same. Only Prompts 1, 2 and 4 change:

- **Prompt 1** builds a REST app with PostgreSQL, seed data, a web page and tests.
- **Prompt 2** adds a time-based "now / next" query. It touches the entities and queries, which suits the crash in Demo 2.
- **Prompt 4** adds a POST with validation, which shows off the Problem Details rule from Demo 3.

If you switch, change the Demo 1 subtitle and Prompt 1 in the slide 2 notes, Prompt 2 in the slide 9 notes and
Prompt 4 in the slide 11 notes. Either edit those slides in the .pptx, or edit the `# 2`, `# 9` and `# 11` blocks in
`build_deck.py` and rebuild. Also change the folder in the checklist.

| App | Folder | Why pick it |
|---|---|---|
| Ferry departures board (default) | `~/demo/ferries` | Visual, and Sydney through and through |
| Café order queue | `~/demo/flatwhite` | Everyone gets it, and it gets a laugh |
| Beach conditions board | `~/demo/beaches` | Visual and local |
| Footy tipping comp | `~/demo/tipping` | Real business rules for Demo 3 (no tips after kick-off) |
| Coastal walks | `~/demo/walks` | Visual and local, and the time logic is a bit harder |
| Java user group meetups | `~/demo/jug` | Speaks to a Java crowd; capacity rules for Demo 3 |
| Fitness class timetable | `~/demo/classes` | Simple domain; booking rules for Demo 3 |

### Café order queue

*Slide subtitle: "A café order queue, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/flatwhite for a café order queue. An order has a customer name, drink (flat white, long black, latte, chai…), size, milk, the barista making it, a status (ordered, making, ready, collected) and the time it was ordered. REST API to place orders, list them, filter by status and barista, and move an order to its next status. Use PostgreSQL. Seed it with a busy morning of 10 orders. Add a simple web page that shows the queue like the screen above the coffee machine. Write tests and make sure they pass.
2. Add an endpoint that returns the orders ready now and the next order up for each barista, with the average wait time per barista over the last hour, from now or from an optional time parameter.
4. Add a POST endpoint to rate a collected order from 1 to 5, rejecting invalid input and orders that haven't been collected.

### Beach conditions board

*Slide subtitle: "A Sydney beach conditions board, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/beaches for a Sydney beach conditions board. A beach has a name (Bondi, Manly, Coogee, Bronte…), and each report has a time, swell height, water temperature, wind, and a flag (green, yellow, red). REST API to list beaches with their latest report, filter by flag, and mark beaches as favourites. Use PostgreSQL. Seed it with 6 beaches and a morning of reports (make the numbers up). Add a simple web page that shows it as a conditions board with the flag colours. Write tests and make sure they pass.
2. Add an endpoint that returns the best beach to swim at right now (green flag, smallest swell) and how each beach's conditions changed since the previous report.
4. Add a POST endpoint to rate today's swim at a beach from 1 to 5, rejecting invalid input and beaches with a red flag.

### Footy tipping comp

*Slide subtitle: "An office footy tipping comp, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/tipping for an office footy tipping competition. There are teams, rounds, and fixtures (home team, away team, venue, kick-off time, final score once played). Players submit a tip for each fixture. REST API to list rounds and fixtures, enter results, and see each player's tips. Use PostgreSQL. Seed it with 8 made-up teams, 2 rounds of fixtures (the first one already played) and 4 players with tips. Add a simple web page with the fixtures and a leaderboard. Write tests and make sure they pass.
2. Add an endpoint that returns the leaderboard (one point per correct tip) and the fixtures in the next round that haven't kicked off yet.
4. Add a POST endpoint to submit a player's tip for a fixture, rejecting tips after kick-off, teams that aren't playing in that fixture, and duplicate tips.

### Coastal walks

*Slide subtitle: "A Sydney coastal walks guide, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/walks for Sydney coastal walks. A walk has a name (Bondi to Coogee, Spit to Manly, Hermitage Foreshore…), start and end points, length in km, typical duration, difficulty, and a status (open, partly closed, closed). REST API to list walks, filter by difficulty and status, and mark walks as favourites. Use PostgreSQL. Seed it with 6 walks. Add a simple web page that lists them as cards with their status. Write tests and make sure they pass.
2. Add an endpoint that returns the open walks you can still finish before sunset if you start now or at an optional start time (use a fixed sunset time in config), shortest first.
4. Add a POST endpoint to report a walk with a 1–5 rating and an optional track condition note, rejecting invalid input and closed walks.

### Java user group meetups

*Slide subtitle: "A Java user group meetup app, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/jug for a Java user group. An event has a title, speaker, venue, start time, and capacity, and members RSVP to events. REST API to list upcoming and past events, see who has RSVP'd, and cancel an RSVP. Use PostgreSQL. Seed it with 4 events (one in the past) and 10 members. Add a simple web page listing upcoming events with seats left. Write tests and make sure they pass.
2. Add an endpoint that returns the next event with seats left, and for each upcoming event how many people are confirmed and how many are on the waiting list.
4. Add a POST endpoint to RSVP to an event, rejecting past events and duplicate RSVPs, and putting people on a waiting list when the event is full.

### Fitness class timetable

*Slide subtitle: "A fitness class timetable, built live by Claude Code"*

1. Create a Quarkus app in ~/demo/classes for a gym's class timetable. A class has a type (yoga, spin, HIIT, pilates…), instructor, studio, start time, duration, and capacity, and members book spots. REST API to list today's classes, filter by type and studio, and see bookings. Use PostgreSQL. Seed it with a day of 10 classes across 3 studios and some bookings. Add a simple web page with the day's timetable and spots left. Write tests and make sure they pass.
2. Add an endpoint that returns the class on now in each studio and the next class that still has free spots.
4. Add a POST endpoint to book a spot in a class, rejecting full classes, classes that have already started, and double bookings.

## Checklist (day before and morning of)

- [ ] Run the full demo end to end 3–4 times; note which tool calls usually show up
- [ ] Warm caches: `~/.m2` (run Prompt 1 once in another directory), JBang, `docker pull postgres` and the doc-search image
- [ ] Call `quarkus_searchDocs` once before going on stage (the first container start is slow)
- [ ] Empty `~/.quarkus/skills/` and delete the demo folder (`~/demo/ferries`, or the one for the app you picked)
- [ ] Pre-allow `mcp__quarkus-agent__*`, edits and bash in the demo directory, so no permission prompts appear on stage
- [ ] Prepared bad edit for Demo 2, tested to fail the way you expect
- [ ] Fallbacks: a finished app on a git branch and a screen recording of each demo; switch if stuck for more than 60 s
- [ ] Large terminal font, notifications off, phone hotspot ready
