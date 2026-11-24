package com.clipstudio.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * Downloads only the DASH initialization data and media fragments that overlap one
 * requested time window. Completed fragment files are retained after failures so a
 * retry can reuse them; partial files are never treated as complete.
 */
internal object IndexedDashDownloader {
    private data class WorkItem(
        val trackIndex: Int,
        val fragment: IndexedDashFragment,
        val headers: Map<String, String>,
        val destination: File
    )

    suspend fun download(
        tracks: List<IndexedDashTrack>,
        startMs: Long,
        endMs: Long,
        directory: File,
        parallelism: Int,
        onBytesFetched: (Long) -> Unit = {}
    ): List<File> = withContext(Dispatchers.IO) {
        require(tracks.isNotEmpty() && tracks.size <= 2)
        require(startMs >= 0L && endMs > startMs)
        require(parallelism in 1..8)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create indexed download storage." }

        val selectedByTrack = tracks.map { track ->
            val media = track.mediaFor(startMs, endMs)
            check(media.isNotEmpty()) { "The DASH index has no media fragments for this time range." }
            listOf(track.initialization) + media
        }
        val work = selectedByTrack.flatMapIndexed { trackIndex, fragments ->
            fragments.map { fragment ->
                val digest = sha256(fragment.url).take(20)
                WorkItem(
                    trackIndex,
                    fragment,
                    tracks[trackIndex].headers,
                    File(directory, "track-$trackIndex-fragment-${fragment.index}-$digest.bin")
                )
            }
        }
        val semaphore = Semaphore(parallelism)
        val completedBytes = java.util.concurrent.atomic.AtomicLong(
            work.sumOf { item -> item.destination.takeIf { it.isFile }?.length() ?: 0L }
        )
        onBytesFetched(completedBytes.get())

        coroutineScope {
            work.map { item ->
                async {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        if (item.destination.isFile && item.destination.length() > 0L) {
                            return@withPermit
                        }
                        downloadOne(item, onChunkBytes = { count ->
                            val total = completedBytes.addAndGet(count)
                            onBytesFetched(total)
                        })
                    }
                }
            }.awaitAll()
        }

        val outputs = ArrayList<File>(tracks.size)
        for ((trackIndex, items) in work.groupBy { it.trackIndex }.toSortedMap()) {
            coroutineContext.ensureActive()
            val output = File(directory, "indexed-track-$trackIndex.mp4")
            val staging = File(directory, "indexed-track-$trackIndex.mp4.part")
            staging.delete()
            try {
                FileOutputStream(staging).buffered().use { sink ->
                    items.sortedBy { it.fragment.index }.forEach { item ->
                        item.destination.inputStream().buffered().use { source -> source.copyTo(sink) }
                    }
                    sink.flush()
                }
                check(staging.isFile && staging.length() > 0L) {
                    "The selected DASH fragments could not be assembled."
                }
                check(staging.renameTo(output)) { "Could not assemble the selected DASH media fragments." }
                outputs += output
            } finally {
                staging.delete()
            }
        }
        outputs
    }

    private suspend fun downloadOne(item: WorkItem, onChunkBytes: (Long) -> Unit) {
        val job = coroutineContext[Job]
        val partial = File(item.destination.absolutePath + ".part")
        partial.delete()
        var lastError: Exception? = null
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            val connection = (URL(item.fragment.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                item.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.connect()
                val status = connection.responseCode
                check(status in 200..299) { "DASH fragment request failed (HTTP $status)." }
                connection.inputStream.use { input ->
                    FileOutputStream(partial).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            job?.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            onChunkBytes(count.toLong())
                        }
                        output.flush()
                    }
                }
                check(partial.isFile && partial.length() > 0L) { "The server returned an empty DASH fragment." }
                check(partial.renameTo(item.destination)) { "Could not save a downloaded DASH fragment." }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                partial.delete()
                if (attempt < 2) Thread.sleep(350L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Could not download DASH fragment ${item.fragment.index + 1}.", lastError)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
