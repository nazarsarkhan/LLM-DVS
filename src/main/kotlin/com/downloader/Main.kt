package com.downloader

import com.downloader.checksum.ChecksumMismatchException
import com.downloader.cli.parseArgs
import com.downloader.core.FileDownloader
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Config error: ${e.message}")
        exitProcess(1)
    }
    val downloader = FileDownloader(parsed.config)
    try {
        runBlocking { downloader.download() }
    } catch (e: ChecksumMismatchException) {
        System.err.println(e.message)
        exitProcess(3)
    } catch (e: IOException) {
        System.err.println("Network/IO error: ${e.message}")
        exitProcess(2)
    } catch (e: IllegalArgumentException) {
        System.err.println("Config error: ${e.message}")
        exitProcess(1)
    }
}
