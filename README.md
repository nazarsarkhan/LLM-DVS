# LLM-DVS — Parallel File Downloader

A CLI file downloader written in Kotlin that fetches files in parallel chunks using HTTP Range requests. Given a URL, it sends a HEAD request to discover file size, splits the file into N byte ranges, downloads each range concurrently, and assembles the pieces into a complete output file.

## Task Requirements

> *"Implement a file downloader which has the ability to download chunks of a file in parallel. You should collect the parts from a web server by specifying a URL."*

| Requirement | Implementation |
|-------------|----------------|
| Download chunks in parallel | `FileDownloader` launches N coroutines on `Dispatchers.IO`, each fetching one byte range |
| Specify a URL | First positional argument: `java -jar downloader.jar <url>` |
| HEAD request → `Accept-Ranges` + `Content-Length` | `FileProber.probe()` — validates both headers before splitting |
| GET with `Range: bytes=<start>-<end>` | `ChunkDownloader.downloadAttempt()` — one request per chunk |
| Combine parts into a complete file | `ChunkAssembler.assemble()` — renames `.part` → output after all chunks finish |
| Unit tests | 54 tests across 10 test files using `MockWebServer` |

## How It Works

```
HEAD <url>  →  Content-Length, Accept-Ranges: bytes
                        │
              split into N byte ranges
                        │
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
GET Range:0-N    GET Range:N-2N   GET Range:2N-3N     (parallel)
        │               │               │
        └───────────────┴───────────────┘
                        │
              write into pre-allocated .part file
                        │
              rename .part → output file
```

Each chunk is written directly at its correct byte offset into a pre-allocated file, so no in-memory assembly is needed.

## Features

- **Parallel chunk downloads** — N concurrent `Range` GET requests via `Dispatchers.IO` coroutines
- **Adaptive chunk count** — auto-selects 1/4/8/16 chunks based on file size; override with `--chunks`
- **Work stealing** — monitor coroutine splits stalled chunks into halves, keeping idle workers busy
- **Resume support** — persists a `.manifest.json` per chunk; interrupted downloads restart from where they left off
- **Per-chunk retry with exponential backoff** — 1 s / 2 s / 4 s… up to `--retries` attempts
- **Single-stream fallback** — if server doesn't return `Accept-Ranges: bytes`, falls back to a plain GET
- **Real-time ANSI progress** — per-chunk progress bars with speed (MB/s) and ETA
- **SHA-256 checksum verification** — `--checksum` validates integrity after download
- **Content-Disposition filename** — uses server-suggested filename when `--output` is omitted
- **Quiet mode** — `--quiet` suppresses all stdout; errors still go to stderr

## Quick Start

### 1 — Start a local web server

```bash
docker run --rm -p 8080:80 \
  -v /path/to/your/local/directory:/usr/local/apache2/htdocs/ \
  httpd:latest
```

Files in that directory are now reachable at `http://localhost:8080/<filename>`.

Verify the server returns the required headers:

```bash
curl -I http://localhost:8080/yourfile.bin
# Expected:
#   Accept-Ranges: bytes
#   Content-Length: <size>
```

### 2 — Build the downloader

```bash
./gradlew shadowJar
# Produces: build/libs/LLM-DVS-1.0.0-all.jar
```

### 3 — Download a file

```bash
java -jar build/libs/LLM-DVS-1.0.0-all.jar \
  http://localhost:8080/yourfile.bin \
  --output out.bin \
  --chunks 8
```

## Usage

```
java -jar build/libs/LLM-DVS-1.0.0-all.jar <url> [options]

Options:
  --output <file>       Output file path (default: derived from Content-Disposition or URL)
  --chunks <N>          Number of parallel chunks (default: adaptive)
  --retries <N>         Max retries per chunk (default: 3)
  --checksum <sha256>   Expected SHA-256 hex to verify after download
  --timeout <N>         Connect and read timeout in seconds (default: 30/60)
  --quiet               Suppress all stdout output (errors still go to stderr)
```

### Examples

```bash
# Basic — output filename derived from URL
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin

# 8 parallel chunks with explicit output path
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/large.bin --output large.bin --chunks 8

# Integrity check (SHA-256)
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin \
  --checksum $(sha256sum /path/to/local/file.bin | cut -d' ' -f1)

# Resume an interrupted download — just rerun the same command
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin --output file.bin --chunks 8

# Custom timeout for slow servers
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/slow.bin --timeout 120

# Quiet mode for scripting
java -jar LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin --quiet && echo "done"
```

## Running the Tests

```bash
./gradlew test
```

All tests use `MockWebServer` — no live server or Docker needed. The suite covers:

| Test file | What it verifies |
|-----------|-----------------|
| `FileProberTest` | HEAD parsing, missing headers, non-2xx responses, Content-Disposition extraction |
| `ChunkDownloaderTest` | 206 happy path, non-206 error messages, Content-Range validation, retry/backoff |
| `FileDownloaderTest` | Full parallel download, resume, single-stream fallback, checksum verification |
| `WorkStealingTest` | Slow-chunk detection, sub-chunk splitting, outstanding count correctness |
| `ManifestManagerTest` | Save/load/mark-done cycle, stale manifest detection |
| `ChunkAssemblerTest` | Part rename, manifest cleanup |
| `ChecksumVerifierTest` | Match, mismatch, missing file |
| `ChunkMathTest` | Adaptive count table, byte range boundaries |
| `ProgressRendererTest` | Channel consumption, render output format |
| `CliArgsTest` | All flags, defaults, error cases |

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Invalid arguments or configuration error |
| 2 | Network / IO error |
| 3 | SHA-256 checksum mismatch |

## Adaptive Chunk Count

When `--chunks` is not specified, the count is chosen by file size:

| File size   | Chunks |
|-------------|--------|
| < 1 MB      | 1      |
| 1 – 10 MB   | 4      |
| 10 – 100 MB | 8      |
| > 100 MB    | 16     |

## Architecture

```
FileProber          HEAD <url> → Content-Length, Accept-Ranges, Content-Disposition
 │
FileDownloader      Splits file into chunks, orchestrates workers, manages resume state
 ├── ManifestManager     Persists completed chunk indices to <output>.manifest.json
 ├── WorkQueue           Channel-backed queue shared across the worker pool
 ├── Worker pool         N coroutines on Dispatchers.IO
 │    └── ChunkDownloader    GET with Range header → streams bytes into RandomAccessFile at correct offset
 ├── WorkStealingMonitor Detects stalled chunks; cancels and re-enqueues as two halves
 └── ProgressRenderer    Drains ChunkProgress channel; redraws ANSI bars on Dispatchers.Default
 │
ChunkAssembler      Renames <output>.part → <output>, deletes manifest
 │
ChecksumVerifier    Optional SHA-256 check
```

## Resume Logic

A `<output>.manifest.json` is written alongside `<output>.part` and updated after each chunk completes. On restart:

1. Manifest matches URL + file size + chunk count → skip completed chunks, fetch the rest.
2. Manifest is stale → restart from scratch.
3. Download succeeds → both `.part` and `.manifest.json` are deleted.

## Project Structure

```
src/
├── main/kotlin/com/downloader/
│   ├── Main.kt                          # Entry point, exit codes
│   ├── cli/CliArgs.kt                   # Argument parsing
│   ├── model/                           # Data classes (Chunk, DownloadConfig, DownloadManifest, …)
│   ├── core/
│   │   ├── FileDownloader.kt            # Orchestrator + work stealing
│   │   ├── FileProber.kt                # HEAD request → server capabilities
│   │   ├── ChunkDownloader.kt           # Single chunk download + retry
│   │   ├── ChunkAssembler.kt            # Rename .part → output
│   │   └── WorkQueue.kt                 # Channel-backed work queue
│   ├── resume/ManifestManager.kt        # Read/write/update manifest
│   ├── progress/ProgressRenderer.kt     # ANSI progress bars
│   ├── checksum/ChecksumVerifier.kt     # SHA-256 verification
│   └── utils/ChunkMath.kt              # Adaptive chunk count + range math
└── test/kotlin/com/downloader/
    ├── CliArgsTest.kt
    ├── FileProberTest.kt
    ├── ChunkDownloaderTest.kt
    ├── FileDownloaderTest.kt
    ├── WorkStealingTest.kt
    ├── ManifestManagerTest.kt
    ├── ChunkAssemblerTest.kt
    ├── ChecksumVerifierTest.kt
    ├── ChunkMathTest.kt
    └── ProgressRendererTest.kt
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `okhttp3` | 4.12.0 | HTTP client + Range requests |
| `kotlinx-coroutines-core` | 1.8.1 | Parallel chunk downloads |
| `kotlinx-serialization-json` | 1.6.3 | Manifest serialization |
| `mockwebserver` | 4.12.0 | In-process HTTP server for tests |
| `junit-jupiter` | 5.10.3 | Test framework |
