---
name: scalex-intellij
description: "JetBrains IntelliJ IDEA integration via MCP Server for Scala/Java projects. Provides compiler-powered code intelligence through a running IDE: build & compile (real compilation errors), run test/app configurations, IDE inspections and warnings, safe semantic rename refactoring, compiler-resolved type documentation, IDE-indexed symbol/text/regex search, build-tool-aware project modules and dependencies, and code formatting. Overlaps with the scalex skill for code navigation — when both are available, scalex-intellij provides compiler-level precision (resolved types, implicits, overloads) while scalex provides fast source-level results without needing the IDE. Use this skill when the user says 'build', 'compile', 'run tests', 'check errors', 'inspections', 'rename across project', 'run configuration', 'modules', 'dependencies', 'reformat', 'IntelliJ', 'IDE', 'MCP server', or when they want compiler-level precision for search or symbol info. If the user's CLAUDE.md or instructions indicate a preference for scalex-intellij, prioritize it over scalex for overlapping tasks like symbol search, find definitions, and code exploration."
---

You have access to `jb-mcp`, a shell script that communicates with a running JetBrains IDE (IntelliJ IDEA, Rider, etc.) via its built-in MCP Server plugin. The script uses Streamable HTTP (`POST /stream`) — each call is a simple request/response with no hanging connections.

## Choosing between scalex-intellij and scalex

Both skills can search symbols, find definitions, and explore code. They differ in how:

| | scalex-intellij (this skill) | scalex |
|---|---|---|
| **Precision** | Compiler-resolved — sees inferred types, implicits, overloads | Source-level — sees what's written in code |
| **Speed** | Fast (~0.1s for search, seconds for builds) | Fast (~0.1-0.5s) |
| **Requirements** | IDE must be running with project open | Just needs a git repo |
| **Unique features** | Build, inspections, run configs, rename refactoring, project modules/deps | Refs with categorization, inheritance trees, package overview, batch commands |

**How to decide:**
- If the user explicitly prefers scalex-intellij (in CLAUDE.md or by asking), use it for everything it can do
- If the task requires compilation, building, running, or IDE-only features — use this skill (scalex can't do these)
- If both could work and no preference is stated, either is fine — scalex is lighter (no IDE needed), scalex-intellij is more precise
- If the IDE isn't running, fall back to scalex

## Before you start

Verify the IDE is reachable:

```bash
bash "<path>/scripts/jb-mcp" projects
```

If this fails with "No JetBrains IDE found", the IDE isn't running. Fall back to scalex or Claude Code tools. Tell the user the IDE needs to be running for this skill.

## Setup

The script lives at `scripts/jb-mcp` next to this SKILL.md. It requires `curl` and `jq` (both standard on macOS/Linux).

**Prerequisites**: JetBrains IDE running with the **MCP Server** plugin enabled (built-in from 2025.2+). The project must be **open** in the IDE.

**Invocation pattern** — use the absolute path directly:

```bash
bash "/absolute/path/to/skills/scalex-intellij/scripts/jb-mcp" <command> [options]
```

Replace with the actual absolute path to the directory containing this SKILL.md.

## Script usage

### Discover open projects

```bash
bash "<path>/scripts/jb-mcp" projects
```

Returns one project path per line. All tool calls need `-w <project-path>` matching one of these.

### List available tools

```bash
bash "<path>/scripts/jb-mcp" tools
```

### Call a tool

```bash
bash "<path>/scripts/jb-mcp" -w /path/to/project call <tool-name> '<json-args>'
```

The script automatically discovers the IDE port, initializes/caches the MCP session, injects `projectPath`, and re-initializes if the session expires.

### Options

| Flag | Description |
|------|-------------|
| `-w <path>` | Project path (must match an open IDE project) |
| `-p <port>` | Explicit IDE port (skips auto-discovery) |
| `-t <seconds>` | Timeout (default: 60). Increase for builds. |
| `-q` | Quiet — suppress status messages |
| `-r` | Raw — return full JSON-RPC response |

Environment variables `JETBRAINS_MCP_PORT`, `JETBRAINS_PROJECT_PATH`, `JETBRAINS_MCP_TIMEOUT` override defaults.

## IDE-exclusive tools

These are the reason to use this skill — no other tool can do these:

### Build & compile

**`build_project`** — Trigger incremental build, get compilation errors with exact locations.

```bash
jb-mcp -w /project call build_project '{}'
jb-mcp -w /project call build_project '{"filesToRebuild":["src/Main.scala"]}'
jb-mcp -w /project call build_project '{"rebuild":true}' -t 120
```

Returns `{isSuccess, problems: [{message, file, line, column}]}`.

**`get_file_problems`** — IDE inspections (errors, warnings) for a file without a full build.

```bash
jb-mcp -w /project call get_file_problems '{"filePath":"src/Main.scala"}'
jb-mcp -w /project call get_file_problems '{"filePath":"src/Main.scala","errorsOnly":false}'
```

### Run configurations

**`get_run_configurations`** — Discover runnable configs.

```bash
jb-mcp -w /project call get_run_configurations '{}'
```

**`execute_run_configuration`** — Run tests, apps, or tasks.

```bash
jb-mcp -w /project call execute_run_configuration '{"configurationName":"MyTestSuite"}'
jb-mcp -w /project call execute_run_configuration '{"configurationName":"MyApp","timeout":120000}'
```

### Semantic refactoring

**`rename_refactoring`** — Safe rename across all references in the project.

```bash
jb-mcp -w /project call rename_refactoring '{"pathInProject":"src/Service.scala","symbolName":"oldName","newName":"newName"}'
```

### Project structure (build-tool-aware)

```bash
jb-mcp -w /project call get_project_modules '{}'
jb-mcp -w /project call get_project_dependencies '{}'
jb-mcp -w /project call get_repositories '{}'
```

### Code formatting

```bash
jb-mcp -w /project call reformat_file '{"path":"src/Main.scala"}'
```

## Overlapping tools (also available in scalex, but with compiler precision)

### Symbol info

**`get_symbol_info`** — Compiler-resolved documentation at a position. Resolves inferred types, implicits, and overloads that scalex can't see.

```bash
jb-mcp -w /project call get_symbol_info '{"filePath":"src/Main.scala","line":10,"column":15}'
```

### Search

IDE-indexed search — fast, with match coordinates and `||` markers around matches:

```bash
jb-mcp -w /project call search_text '{"q":"HttpClient","limit":10}'
jb-mcp -w /project call search_regex '{"q":"extends.*Module","paths":["**/*.mill"]}'
jb-mcp -w /project call search_symbol '{"q":"Builder","limit":5}'
jb-mcp -w /project call search_file '{"q":"**/*.scala","paths":["web/core/"]}'
```

`search_symbol` is semantic — finds classes, methods, fields by identifier.

### Terminal

```bash
jb-mcp -w /project call execute_terminal_command '{"command":"mill web.core.compile","timeout":120000}'
```

Requires user confirmation in IDE unless "Brave Mode" is enabled.

## Common workflows

### Build, find errors, fix

```bash
# 1. Build
jb-mcp -w /project call build_project '{}'
# 2. Inspect specific file
jb-mcp -w /project call get_file_problems '{"filePath":"src/broken/File.scala"}'
# 3. Fix with Claude Code Edit tool
# 4. Rebuild to verify
jb-mcp -w /project call build_project '{"filesToRebuild":["src/broken/File.scala"]}'
```

### Run tests

```bash
jb-mcp -w /project call get_run_configurations '{}'
jb-mcp -w /project call execute_run_configuration '{"configurationName":"All Tests","timeout":120000}'
```

### Deep symbol understanding

```bash
# Compiler-level: resolved types, full documentation
jb-mcp -w /project call get_symbol_info '{"filePath":"src/MyTrait.scala","line":5,"column":7}'
```

### Safe rename

```bash
jb-mcp -w /project call rename_refactoring '{"pathInProject":"src/Service.scala","symbolName":"processData","newName":"handleData"}'
```

## Response timing

| Tool | Typical time |
|------|-------------|
| `search_*`, `read_file`, `get_project_modules` | < 1s |
| `get_file_problems` | 3–10s |
| `build_project` | 5–60s+ |
| `execute_run_configuration` | Depends on task |

Use `-t` for slow operations: `jb-mcp -t 120 -w /project call build_project '{"rebuild":true}'`

## Troubleshooting

- **"No JetBrains IDE found"** — IDE not running or MCP Server plugin not enabled
- **"doesn't correspond to any open project"** — Run `jb-mcp projects` and use `-w` with an exact match
- **Session errors** — Script auto-recovers. Force new session with `jb-mcp init`
- **Slow responses** — Build/inspection tools do real work. Increase timeout with `-t`

## Detailed tool reference

For complete parameter documentation of all 27 tools, read `references/tools.md` in this skill directory.
