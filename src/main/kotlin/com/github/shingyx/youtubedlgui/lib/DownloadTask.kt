package com.github.shingyx.youtubedlgui.lib

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.concurrent.Task
import java.io.IOException
import java.lang.IllegalArgumentException
import java.net.URL
import java.net.URLDecoder
import javafx.beans.property.ReadOnlyStringProperty

class DownloadTask(private val url: String) : Task<Unit>() {
    private val processBuilder: ProcessBuilder
    private var videoId: String? = null

    private val speed = SimpleStringProperty(this, "speed", "-")
    private val eta = SimpleStringProperty(this, "eta", "-")

    init {
        val format = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]"
        processBuilder = ProcessBuilder(
                "\"${Config.youtubeDlPath}\"",
                "--ffmpeg-location",
                "\"${Config.ffmpegPath}\"",
                "-o",
                "\"${Config.outputDir}/%(title)s.%(ext)s\"",
                "-f",
                "\"$format\"",
                "--newline",
                url
        )
        processBuilder.redirectErrorStream(true)
    }

    override fun call() {
        updateMessage("Initializing")
        val process = processBuilder.start()
        process.inputStream.bufferedReader().use {
            updateTitle(url)
            var completeMessage = "Complete"
            try {
                var line = it.readLine()
                while (line != null && !isCancelled) {
                    line = line.trim()
                    processLine(line)
                    if (line.matches(".+has already been downloaded( and merged)?".toRegex())) {
                        completeMessage = "Already downloaded"
                    }
                    line = it.readLine()
                }
            } catch (e: Exception) {
                completeMessage = "Error: ${e.message}"
            }

            if (isCancelled) {
                completeMessage = "Cancelled"
                process.destroy()
            }
            updateProgress(100, 100)
            updateMessage(completeMessage)
            updateSpeed("-")
            updateEta("-")
        }
    }

    private fun processLine(line: String) {
        println(line)
        val parts = line.split("\\s+".toRegex())
        when (parts[0]) {
            "[youtube]" -> {
                if (videoId == null) {
                    videoId = parts[1].dropLast(1)
                    // Reading the real title from the input stream doesn't always work on Windows - just request it
                    Thread({
                        try {
                            val videoInfoUrl = URL("https://youtube.com/get_video_info?video_id=$videoId")
                            val response = videoInfoUrl.openStream().bufferedReader().use { it.readText() }
                            val decoded = URLDecoder.decode(response, Charsets.UTF_8.name())
                            val videoTitle = decoded.split("&").find { it.startsWith("title=") }?.substringAfter("=")
                            if (!videoTitle.isNullOrEmpty()) {
                                updateTitle(videoTitle)
                            }
                        } catch (e: IOException) {
                            // it tried
                        }
                    }).start()
                }
            }
            "[download]" -> {
                updateMessage("Downloading")
                if (parts[1].endsWith("%")) {
                    val percentage = Math.min(99.9, parts[1].dropLast(1).toDouble())
                    updateProgress(percentage, 100.0)
                    if (line.contains("ETA") && !line.contains("Unknown")) {
                        updateSpeed(parts[5])
                        updateEta(parts[7])
                    }
                }
            }
            "ERROR:" -> {
                throw IllegalArgumentException(line.substringAfter("ERROR:").trim())
            }
        }
    }

    @Suppress("unused")
    fun speedProperty(): ReadOnlyStringProperty {
        return speed
    }

    private fun updateSpeed(speed: String) {
        Platform.runLater({ this.speed.set(speed) })
    }

    @Suppress("unused")
    fun etaProperty(): ReadOnlyStringProperty {
        return eta
    }

    private fun updateEta(eta: String) {
        Platform.runLater({ this.eta.set(eta) })
    }
}
