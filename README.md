# LLM-DVS — Parallel File Downloader

A production-quality CLI file downloader written in Kotlin that fetches files in parallel chunks using HTTP Range requests, with work stealing, resume support, and real-time progress rendering.

## Features

- **Parallel chunk downloads** — splits files into N chunks, downloads them concurrently via `Dispatchers.IO`
- **Work stealing** — a monitor coroutine detects stalled chunks and splits them into two halves, keeping idle workers busy until completion
- **Adaptive chunk count** — automatically picks 1/4/8/16 chunks based on file size; override with `--chunks`
- **Resume / partial download** — persists a `.manifest.json` after each chunk; restarting skips already-completed chunks
- **Per-chunk retry with exponential backoff** — each chunk retries up to N times (1 s, 2 s, 4 s…)
- **Graceful single-stream fallback** — if the server doesn't support `Accept-Ranges`, falls back to a regular GET
- **Real-time ANSI progress** — per-chunk progress bars, total speed (MB/s), and ETA rendered in-place
- **SHA-256 checksum verification** — optional `--checksum` flag validates file integrity after download
- **Content-Disposition filename** — uses the server-suggested filename when `--output` is not specified
- **Quiet mode** — `--quiet` suppresses all stdout for scripting; errors still go to stderr

## Requirements

- JDK 21+
- Gradle 9+ (or use the included wrapper: `./gradlew`)

## Build

```bash
# Run tests
./gradlew test

# Build fat JAR (includes all dependencies)
./gradlew shadowJar
# Output: build/libs/LLM-DVS-1.0.0-all.jar
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
# Basic download
java -jar LLM-DVS-1.0.0-all.jar https://example.com/file.bin

# Explicit chunk count and output path
java -jar LLM-DVS-1.0.0-all.jar https://example.com/large.bin --output large.bin --chunks 8

# With integrity check
java -jar LLM-DVS-1.0.0-all.jar https://example.com/file.bin --checksum a3f1...

# Resume an interrupted download — just rerun the same command
java -jar LLM-DVS-1.0.0-all.jar https://example.com/file.bin --output file.bin --chunks 8

# Custom timeout (useful for slow servers)
java -jar LLM-DVS-1.0.0-all.jar https://example.com/slow.bin --timeout 120

# Quiet mode for scripting
java -jar LLM-DVS-1.0.0-all.jar https://example.com/file.bin --quiet && echo "done"
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Invalid arguments or configuration error |
| 2 | Network / IO error |
| 3 | SHA-256 checksum mismatch |

## Adaptive Chunk Count

| File size   | Chunks |
|-------------|--------|
| < 1 MB      | 1      |
| 1 – 10 MB   | 4      |
| 10 – 100 MB | 8      |
| > 100 MB    | 16     |

## Architecture

```
URL
 │
 ▼
FileProber          HEAD request → ContentLength, Accept-Ranges, Content-Disposition
 │
 ▼
FileDownloader      Orchestrator: resolves output path, manages manifest, launches coroutines
 ├── ManifestManager     Persists chunk completion state to <output>.manifest.json
 ├── WorkQueue           Channel-backed queue shared by the worker pool
 ├── Worker pool         N coroutines on Dispatchers.IO, each pulling chunks from WorkQueue
 │    └── ChunkDownloader    Sends Range request, streams bytes into RandomAccessFile, retries on failure
 ├── WorkStealingMonitor Polls active chunks; splits slow ones into two sub-chunks
 └── ProgressRenderer    Consumes ChunkProgress events; redraws ANSI bars on Dispatchers.Default
 │
 ▼
ChunkAssembler      Renames <output>.part → <output>, deletes manifest
 │
 ▼
ChecksumVerifier    Optional SHA-256 validation
```

## Resume Logic

On each chunk completion the downloader writes a `<output>.manifest.json` file alongside the `.part` file. On restart:

1. If the manifest matches the URL, file size, and chunk count → resumes, skipping done chunks.
2. If the manifest is stale (URL or parameters changed) → restarts from scratch.
3. On success → both `.part` and `.manifest.json` are deleted.

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
| `mockwebserver` | 4.12.0 | Test HTTP server |
| `junit-jupiter` | 5.10.3 | Test framework |

## Manual End-to-End Test

```bash
# Serve a local file
docker run --rm -p 8080:80 -v /path/to/dir:/usr/local/apache2/htdocs/ httpd:latest

# Download with 8 chunks
java -jar build/libs/LLM-DVS-1.0.0-all.jar http://localhost:8080/largefile.bin \
     --output out.bin --chunks 8

# Resume test: kill mid-download (Ctrl+C), rerun same command
# → only missing chunks are fetched

# Checksum test
java -jar build/libs/LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin \
     --checksum $(sha256sum /path/to/dir/file.bin | cut -d' ' -f1)

# Quiet mode (no stdout)
java -jar build/libs/LLM-DVS-1.0.0-all.jar http://localhost:8080/file.bin --quiet
echo "Exit code: $?"
```
