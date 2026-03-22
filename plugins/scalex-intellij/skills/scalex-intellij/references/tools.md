# JetBrains MCP Server — Tool Reference

Complete parameter reference for all tools. Organized by category.

## Table of Contents

- [Code Intelligence](#code-intelligence)
- [Build & Execution](#build--execution)
- [Project Structure](#project-structure)
- [Search](#search)
- [File Reading](#file-reading)
- [File Operations](#file-operations)
- [File Discovery](#file-discovery)
- [Terminal](#terminal)
- [VCS](#vcs)
- [Notebook](#notebook)

---

## Code Intelligence

### `get_symbol_info`

Compiler-resolved documentation and declaration for the symbol at a position. Equivalent to IDE's Quick Documentation.

```json
{"filePath": "src/Main.scala", "line": 10, "column": 15}
```

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `filePath` | string | yes | Path relative to project root |
| `line` | int | yes | 1-based line number |
| `column` | int | yes | 1-based column number |

Returns: `{symbolInfo: {name, declarationText, declarationFile, declarationLine, language}, documentation}`

### `rename_refactoring`

Safe semantic rename that updates all references across the project.

```json
{"pathInProject": "src/Service.scala", "symbolName": "oldName", "newName": "newName"}
```

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `pathInProject` | string | yes | Path relative to project root |
| `symbolName` | string | yes | Exact current name of the symbol |
| `newName` | string | yes | New name for the symbol |

### `reformat_file`

Apply IDE formatting rules (respects .scalafmt.conf, .editorconfig, etc.).

```json
{"path": "src/Main.scala"}
```

---

## Build & Execution

### `build_project`

Trigger incremental or full build. Returns compiler errors with file:line:col.

```json
{"rebuild": false}
{"filesToRebuild": ["src/Main.scala"]}
```

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `rebuild` | bool | no | Full rebuild (default: false) |
| `filesToRebuild` | string[] | no | Only compile these files |
| `timeout` | int | no | Timeout in ms |

Returns: `{isSuccess, problems: [{message, kind, file, line, column}], timedOut}`

### `get_file_problems`

IDE inspections — real compiler errors and warnings without triggering a build. Takes a few seconds for analysis.

```json
{"filePath": "src/Main.scala", "errorsOnly": true}
```

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `filePath` | string | yes | Path relative to project root |
| `errorsOnly` | bool | no | Only errors, skip warnings (default: true) |
| `timeout` | int | no | Timeout in ms (default: 10000) |

Returns: `{filePath, errors: [{severity, description, lineContent, line, column}], timedOut}`

### `get_run_configurations`

List available run configurations (tests, apps, tasks).

Returns: `{configurations: [{name, description?, commandLine?, workingDirectory?, environment?}]}`

### `execute_run_configuration`

Run a specific configuration and wait for it to complete.

```json
{"configurationName": "MyTest"}
```

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `configurationName` | string | yes | Name from `get_run_configurations` |
| `timeout` | int | no | Timeout in ms |
| `maxLinesCount` | int | no | Max output lines |
| `truncateMode` | enum | no | START, MIDDLE, END, NONE |

Returns: `{exitCode, timedOut, output}`

---

## Project Structure

### `get_project_modules`

List all modules with their types (as understood by the build tool).

Returns: `{modules: [{name, type}]}`

### `get_project_dependencies`

All library dependencies from the build tool.

Returns: `{dependencies: [{name}]}`

### `get_repositories`

List VCS roots in the project.

Returns: `{roots: [{pathRelativeToProject, vcsName}]}`

---

## Search

All search tools return: `{items: [{filePath, startLine, startColumn, endLine, endColumn, startOffset, endOffset, lineText}], more}`

Match text is surrounded by `||` markers in `lineText`.

### `search_text`

Fast substring search across project files.

```json
{"q": "def main", "limit": 10}
{"q": "HttpClient", "paths": ["src/**", "!**/test/**"], "limit": 20}
```

### `search_regex`

Regex pattern search.

```json
{"q": "extends.*Module", "paths": ["**/*.mill"], "limit": 10}
```

### `search_symbol`

Semantic symbol search — finds classes, methods, fields by name.

```json
{"q": "Builder", "limit": 5}
```

### `search_file`

Find files by glob pattern.

```json
{"q": "**/*.scala", "paths": ["web/core/"], "limit": 20}
```

**Path filter rules** (apply to all search tools):
- Glob patterns relative to project root
- `!` prefix excludes: `["src/**", "!**/test/**"]`
- Trailing `/` expands to `/**`: `["web/core/"]` → `["web/core/**"]`
- No `/` treated as `**/pattern`

### `search_in_files_by_text` / `search_in_files_by_regex`

Legacy search tools with directory and file mask filters.

```json
{"searchText": "TODO", "directoryToSearch": "src", "fileMask": "*.scala", "maxUsageCount": 50}
{"regexPattern": "TODO.*fix", "caseSensitive": false}
```

Returns: `{entries: [{filePath, lineNumber, lineText}], probablyHasMoreMatchingEntries, timedOut}`

---

## File Reading

### `read_file`

Advanced file reading with 5 modes.

**Slice** — read N lines from a start position:
```json
{"file_path": "build.mill", "mode": "slice", "start_line": 1, "max_lines": 50}
```

**Lines** — read inclusive range:
```json
{"file_path": "build.mill", "mode": "lines", "start_line": 10, "end_line": 30}
```

**Line columns** — read a column range:
```json
{"file_path": "src/Main.scala", "mode": "line_columns", "start_line": 5, "start_column": 10, "end_line": 5, "end_column": 40}
```

**Offsets** — read by byte offset:
```json
{"file_path": "src/Main.scala", "mode": "offsets", "start_offset": 100, "end_offset": 500}
```

**Indentation** — smart block extraction (includes headers and siblings):
```json
{"file_path": "src/Main.scala", "mode": "indentation", "start_line": 38, "max_lines": 30}
```

| Param | Type | Description |
|-------|------|-------------|
| `context_lines` | int | Extra lines around the range |
| `max_levels` | int | Indentation mode: max indent levels (0 = anchor only) |
| `include_siblings` | bool | Indentation mode: include sibling blocks |
| `include_header` | bool | Indentation mode: include header comments/annotations |

Output format: `L<lineNumber>: <content>` per line. Max line length 500 chars.

### `get_file_text_by_path`

Simple file read with truncation.

```json
{"pathInProject": "build.mill", "maxLinesCount": 100, "truncateMode": "START"}
```

---

## File Operations

### `create_new_file`

```json
{"pathInProject": "src/NewFile.scala", "text": "package example\n\nobject NewFile", "overwrite": false}
```

### `replace_text_in_file`

Find and replace with auto-save.

```json
{"pathInProject": "src/Main.scala", "oldText": "val x = 1", "newText": "val x = 2", "replaceAll": true}
```

### `open_file_in_editor`

```json
{"filePath": "src/Main.scala"}
```

### `get_all_open_file_paths`

Returns: `{activeFilePath, openFiles: [...]}`

---

## File Discovery

### `list_directory_tree`

```json
{"directoryPath": ".", "maxDepth": 3}
```

Returns: `{traversedDirectory, tree, errors}`

### `find_files_by_glob`

```json
{"globPattern": "**/*.scala", "fileCountLimit": 100}
```

### `find_files_by_name_keyword`

Fast name substring search using IDE indexes.

```json
{"nameKeyword": "AppServer"}
```

---

## Terminal

### `execute_terminal_command`

Run a shell command in the IDE's integrated terminal. Requires user confirmation unless "Brave Mode" is enabled.

```json
{"command": "mill web.core.compile", "timeout": 120000}
```

| Param | Type | Description |
|-------|------|-------------|
| `command` | string | Shell command |
| `executeInShell` | bool | Use user's shell (bash/zsh) |
| `reuseExistingTerminalWindow` | bool | Reuse existing terminal |
| `timeout` | int | Timeout in ms (default: 60000) |
| `maxLinesCount` | int | Max output lines |
| `truncateMode` | enum | START, MIDDLE, END, NONE |

Returns: `{is_timed_out, command_exit_code, command_output}`

---

## VCS

### `get_repositories`

```json
{}
```

Returns: `{roots: [{pathRelativeToProject, vcsName}]}`

---

## Notebook

### `runNotebookCell`

Execute Jupyter notebook cells.

```json
{"file_path": "/absolute/path/demo.ipynb"}
{"file_path": "/absolute/path/demo.ipynb", "cell_id": "13c5cec416369e19"}
```
