package com.downloader.cli

import com.downloader.model.DownloadConfig
import java.net.URI

data class ParsedArgs(
    val config: DownloadConfig,
)

private fun usage(testMode: Boolean): Nothing {
    val msg = """
        Usage: downloader <url> [options]

        Options:
          --output <file>       Output file path (default: derived from URL)
          --chunks <N>          Number of parallel chunks (default: adaptive)
          --retries <N>         Max retries per chunk (default: 3)
          --checksum <sha256>   Expected SHA-256 hex to verify after download
          --timeout <N>         Connect and read timeout in seconds (default: 30/60)

        Examples:
          downloader https://example.com/file.bin
          downloader https://example.com/file.bin --output out.bin --chunks 8
          downloader https://example.com/file.bin --checksum abc123...
    """.trimIndent()
    if (testMode) throw IllegalArgumentException(msg)
    System.err.println(msg)
    System.exit(1)
    error("unreachable")
}

fun parseArgs(args: Array<String>, testMode: Boolean = false): ParsedArgs {
    if (args.isEmpty()) usage(testMode)

    val url = args[0]
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        if (testMode) throw IllegalArgumentException("Error: first argument must be a URL (http:// or https://)")
        System.err.println("Error: first argument must be a URL (http:// or https://)")
        usage(testMode)
    }

    var output: String? = null
    var chunks: Int? = null
    var retries = 3
    var checksum: String? = null
    var timeout: Int? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--output"   -> { output   = args.getOrNull(++i) ?: usage(testMode) }
            "--chunks"   -> { chunks   = args.getOrNull(++i)?.toIntOrNull() ?: usage(testMode) }
            "--retries"  -> { retries  = args.getOrNull(++i)?.toIntOrNull() ?: usage(testMode) }
            "--checksum" -> { checksum = args.getOrNull(++i) ?: usage(testMode) }
            "--timeout"  -> { timeout  = args.getOrNull(++i)?.toIntOrNull() ?: usage(testMode) }
            else -> {
                if (testMode) throw IllegalArgumentException("Unknown option: ${args[i]}")
                System.err.println("Unknown option: ${args[i]}")
                usage(testMode)
            }
        }
        i++
    }

    val derivedOutput = output ?: run {
        val path = URI(url).path
        path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "downloaded_file"
    }

    return ParsedArgs(
        config = DownloadConfig(
            url               = url,
            outputFile        = derivedOutput,
            chunkCount        = chunks,
            maxRetries        = retries,
            checksum          = checksum,
            connectTimeoutSec = timeout ?: 30,
            readTimeoutSec    = timeout ?: 60,
        )
    )
}
