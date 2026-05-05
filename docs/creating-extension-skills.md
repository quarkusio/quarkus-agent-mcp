# Creating Extension Skills: A Practical Guide

This guide walks you through discovering what your extension's AI skill should contain by observing how an agent actually uses your extension - and where it struggles.

## Overview

The idea is simple: watch an AI agent try to build something with your extension, see where it gets stuck, and turn those learnings into a skill file that ships with your extension. You'll use two separate Claude Code sessions - one to help you author the skill, and one to test it.

## Prerequisites

- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed
- [Quarkus Agent MCP](https://github.com/quarkusio/quarkus-agent-mcp) configured in Claude Code
- Your extension published to a Maven repository (local or remote)

## Setup

Open two terminal windows. We'll call them **Worker** and **Tester**.

| Session  | Model     | Purpose                                          |
|----------|-----------|--------------------------------------------------|
| Worker   | Opus      | Crafts test prompts, analyzes struggles, writes skills |
| Tester   | Sonnet    | Simulates a real developer using your extension   |

Using Sonnet for the Tester is intentional - it's the model most developers will use day-to-day, so if the skill helps Sonnet, it helps the majority.

Start both sessions:

```bash
# Terminal 1 - Worker (Opus)
claude --model opus

# Terminal 2 - Tester (Sonnet, the default)
claude
```

## Step 1: Create a test prompt

In the **Worker** session, ask it to write a realistic prompt - something a developer would actually ask when building an app with your extension.

> Write me a realistic developer prompt that would test building an application using the `quarkus-my-extension` extension. The prompt should be something a developer would naturally ask - not a test scenario, but a real task. Make it specific enough to cover the key features of the extension.

The Worker will craft a prompt like:

> "Create a Quarkus application that uses [your extension] to [do something realistic]. It should [specific requirement 1] and [specific requirement 2]."

**Tip:** Start with a straightforward prompt for your first pass. You can create more complex prompts later to test edge cases and advanced features.

## Step 2: Run the prompt

Copy the prompt from the Worker and paste it into the **Tester** session. Let the agent work.

Watch it carefully. Don't intervene unless it's completely stuck - you want to see where it naturally struggles.

## Step 3: Observe and assist

As the Tester works, you'll likely see it:

- Use the wrong annotations or API patterns
- Miss required configuration
- Write tests incorrectly for your extension
- Get stuck on integration patterns with other extensions
- Make mistakes that the docs cover but aren't obvious

When the agent gets stuck, help it just enough to move forward. Take note of every place where you had to intervene - these are your skill candidates.

## Step 4: Evaluate the result

Once the task is complete, ask yourself:

**Was it smooth?** If the agent completed the task without issues, you might not need a skill for this prompt. Consider testing with a more complex prompt - one that tests advanced features, edge cases, or multi-extension integration. Go back to Step 1.

**Did it struggle?** If you had to intervene, continue to Step 5.

## Step 5: Identify the gaps

In the **Tester** session, ask:

> What did you struggle with while building this application? What was unclear or difficult? What information would have helped you get it right the first time? Were there times you wished you could query or act on the running application?

The agent will tell you things like:
- "I wasn't sure which annotation to use for X"
- "I didn't know that Y requires Z configuration"
- "Testing was difficult because I didn't know about the test utility class"
- "I tried to do X manually but there's a CDI bean that handles it"
- "I wanted to check the current state of X but had no way to query it"

These responses point to two different remedies:

- **Skill gap** - the agent lacked knowledge (wrong annotations, missing config, testing patterns). Fix this with a skill file that teaches the right patterns.
- **Tool gap** - the agent needed to query or act on the running app (inspect state, trigger an action, fetch generated schemas). Fix this with a [Dev MCP tool](https://quarkus.io/guides/dev-mcp#dev-mcp-tools) that exposes that capability at dev time.

A single extension might need both. Skills teach the agent *how* to write code; Dev MCP tools let the agent *interact* with the running app.

## Step 6: Create the skill

Switch to the **Worker** session. Give it the Tester's feedback and ask it to create a skill file.

> The agent struggled with the following when using our extension:
>
> [paste the Tester's response]
>
> Based on this, create a `quarkus-skill.md` file for our extension. Follow the extension skills guide at https://quarkus.io/guides/dev-mcp#extension-skills for the format and best practices.

**Review the output before testing.** Skills should be concise and actionable - patterns, pitfalls, and testing guidance. Not a tutorial. Remove anything too verbose or overly prescriptive; an over-constrained skill can be worse than no skill at all.

## Step 7: Test the skill as an override

Before shipping the skill with your extension, test it as a user-level override. In the **Worker** session, ask it to save the skill:

> Save the skill file you just created to `~/.quarkus/skills/quarkus-my-extension/SKILL.md`

The Quarkus Agent MCP server picks up user-level skills automatically - no restart needed.

**Important:** Open a **new** Tester session for this test. Don't reuse the old one - the conversation history would skew the results. You need to prove the skill alone is sufficient.

```bash
# Terminal 2 - fresh Tester session
claude
```

Run the same prompt again. This time the agent should have the skill available and handle the task more smoothly.

## Step 8: Iterate if needed

If the agent still struggles with some aspects, go back to Step 5 - identify new gaps, update the skill, and re-test.

If it works well, you have a validated skill.

## Step 9: Ship the skill with your extension

In the **Worker** session, ask it to move the validated skill into your extension:

> Move `~/.quarkus/skills/quarkus-my-extension/SKILL.md` to `my-extension/deployment/src/main/resources/META-INF/quarkus-skill.md` and delete the override. See https://quarkus.io/guides/dev-mcp#extension-skills for the expected format.

No `pom.xml` changes are needed - the Quarkus build discovers skill files automatically.

## Tips for better skills

- **Test multiple prompts.** One prompt won't catch all rough edges. Try 2-3 of increasing complexity - basic CRUD, multi-extension integration, and something that hits a non-obvious config or edge case.
- **Focus on what the agent can't figure out from docs alone.** Correct annotation combos, required config that isn't obvious, common pitfalls, testing patterns - these are the high-value things for a skill.
- **Keep it concise.** A skill is not a tutorial. If you're writing more than a page, you're probably including too much.
- **Don't duplicate what the build composes.** Extension description, guide links, config properties, and Dev MCP tools are added automatically. Your skill file should focus on patterns and pitfalls.
- **Consider Dev MCP tools, not just skills.** If the agent's problem is "I need to know X about the running app," a skill won't help - it needs a Dev MCP tool. Skills and tools complement each other: skills provide knowledge at coding time, tools provide runtime access. See the [Dev MCP tools guide](https://quarkus.io/guides/dev-mcp#dev-mcp-tools) for how to add tools to your extension.
