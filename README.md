# Storyweave

Storyweave is a dependency-free Java 21 and browser implementation of a multiplayer storytelling game. Each player
receives a private variation of the same premise, then players take turns adding one word or punctuation mark to a
shared story. Individual clocks determine when players are eliminated, and an OpenAI-compatible model compares the
finished story with each private version to produce the final ranking.

## Requirements

- JDK 21 or newer
- A modern browser
- Optional: an OpenAI-compatible chat-completions URL and API key

## Quick start (offline mode)

Compile from the project root in PowerShell:

```powershell
New-Item -ItemType Directory -Force out\production | Out-Null
javac --add-modules jdk.httpserver -d out\production (Get-ChildItem src\backend\storygame\*.java).FullName
java --add-modules jdk.httpserver -cp out\production backend.storyweave.Main --local --theme "a city above the clouds" --players 2
```

Run the command above as a complete command in a PowerShell terminal. If you run the application from IntelliJ IDEA,
create an Application run configuration with these fields instead:

- Main class: `storyweave`
- VM options: `--add-modules jdk.httpserver`
- Program arguments: `--local --theme "a city above the clouds" --players 2`

Do not put `java`, `-cp`, the classpath, or the main class in **Program arguments**. Those parts configure the Java
launcher; if they are passed to the application, `Main` reports an error such as `Unexpected argument: -cp`.

Open `http://localhost:8080` in one browser window per player. Offline mode generates deterministic sample story
variations and uses word-overlap scoring, making it useful for setup and demos without credentials.

The backend creates a new timestamped log file for each run in both log categories:
- `logs\http\<timestamp>.log`: every HTTP request (method/path/body) with its response status and payload
- `logs\llm\<timestamp>.log`: every remote LLM prompt with the corresponding provider response body

Both files use the same UTC startup timestamp in their filename, and all logs from that process are appended to those
files until the program stops.

## Standalone executable JAR

Build a self-contained application JAR containing the backend, frontend, and LLM prompt templates:

```powershell
.\build-jar.ps1
```

The output is `dist\storyweave.jar`. It can be copied elsewhere and run without the project source tree (a Java 21 or
newer runtime is still required):

```powershell
java --add-modules jdk.httpserver -jar dist\storyweave.jar --local --theme "a city above the clouds" --players 2
```

Remote LLM options work with the JAR in the same way as the classpath launch described below.

## Remote LLM mode

Start without `--local`; the program prompts for the chat-completions URL, API key, model name, and story theme,
verifies the remote connection, then prints the server port:

```powershell
java --add-modules jdk.httpserver -cp out\production backend.storyweave.Main
```

Non-interactive configuration is also supported:

```powershell
java --add-modules jdk.httpserver -cp out\production backend.storyweave.Main `
  --url "https://api.openai.com/v1/chat/completions" --key "..." --model "gpt-4o-mini" `
  --theme "an expedition beneath the ice" --port 8080 --players 3
```

The endpoint must accept the common `model`, `messages`, `temperature`, and `max_tokens` request fields and return text
at `choices[0].message.content`.

## Game options

| Option | Default | Purpose |
| --- | ---: | --- |
| `--port` | 8080 | HTTP server port; use `0` to choose an available port |
| `--players` | (required) | Required players (2-12) |
| `--read-seconds` | 45 | Private-story reading period |
| `--player-seconds` | 90 | Total clock available to each player |
| `--turn-seconds` | 30 | Maximum duration of one turn |

Names must contain 1-24 visible ASCII characters with no whitespace. A turn accepts one Unicode word (including an
internal apostrophe or hyphen) or exactly one punctuation mark. Existing shared text is never directly editable.

## Tests

The test suite uses a controllable clock and a real local HTTP server; no external services are contacted:

```powershell
New-Item -ItemType Directory -Force out\test | Out-Null
javac --add-modules jdk.httpserver -d out\test `
  (Get-ChildItem src\backend\storygame\*.java).FullName `
  (Get-ChildItem test\backend\storygame\*.java).FullName
java --add-modules jdk.httpserver -ea -cp out\test backend.storyweave.AllTests
```

## Project layout

- `storyweave`: game engine, HTTP server, JSON support, and LLM clients
- `src/backend/prompts`: editable prompt formats for generation, similarity, and error deductions
- `src/frontend`: responsive HTML, CSS, and JavaScript client
- `test/backend/storyweave`: engine, validation, JSON, and HTTP integration tests

All shared state is controlled by synchronized backend operations. Browser clients poll snapshots twice per second, so
turns, clocks, player lists, story text, and results remain consistent across participants.