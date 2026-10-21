package app.echomind.models

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast, resumable downloads for multi-gigabyte models.
 *
 * Android's DownloadManager uses one connection, and model CDNs cap the speed
 * of each connection (measured ~0.6 MB/s for Gemma on the iQOO), so a 2.6 GB
 * model took over an hour. This splits the file into ranges fetched in
 * parallel, writes each straight to its place in the file, and records
 * progress beside it, so a killed app or a dropped network resumes where
 * it stopped instead of starting over.
 */
object Downloader {
    private const val TAG = "EchoDownloader"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        // One TCP connection per range. Over HTTP/2 OkHttp would multiplex every
        // range onto a single connection, and a slow or lossy link (2.4 GHz Wi-Fi)
        // then caps the whole download at one connection's speed.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .build()

    private class Chunk(val start: Long, val end: Long, var done: Long) {
        val length get() = end - start + 1
        val complete get() = done >= length
    }

    private data class Probe(val url: String, val total: Long, val ranged: Boolean)

    /** Resolves redirects (model hosts redirect to signed CDN links) and learns the size. */
    private fun probe(url: String): Probe {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { r ->
            val finalUrl = r.request.url.toString()
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("no size from server")
                return Probe(finalUrl, total, true)
            }
            if (r.isSuccessful) return Probe(finalUrl, r.body.contentLength(), false)
            throw IOException("HTTP ${r.code}")
        }
    }

    private fun stateFile(dest: File) = File(dest.path + ".state")

    private fun load(dest: File, total: Long): List<Chunk>? {
        val f = stateFile(dest)
        if (!f.exists() || !dest.exists() || dest.length() != total) return null
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != total.toString()) return null
        return lines.drop(1).mapNotNull { l ->
            val p = l.split(' ').mapNotNull { it.toLongOrNull() }
            if (p.size == 3) Chunk(p[0], p[1], p[2]) else null
        }.takeIf { it.isNotEmpty() }
    }

    private fun save(dest: File, total: Long, chunks: List<Chunk>) {
        val tmp = File(stateFile(dest).path + ".tmp")
        tmp.writeText(buildString {
            append(total).append('\n')
            synchronized(chunks) { chunks.forEach { append("${it.start} ${it.end} ${it.done}\n") } }
        })
        tmp.renameTo(stateFile(dest))
    }

    /** Progress so far from the saved state, for showing before the download resumes. */
    fun savedProgress(dest: File): Float? {
        val f = stateFile(dest)
        if (!f.exists()) return null
        val lines = f.readLines().filter { it.isNotBlank() }
        val total = lines.firstOrNull()?.toLongOrNull() ?: return null
        val done = lines.drop(1).sumOf { it.split(' ').getOrNull(2)?.toLongOrNull() ?: 0 }
        return if (total > 0) (done.toDouble() / total).toFloat() else null
    }

    fun discard(dest: File) {
        dest.delete()
        stateFile(dest).delete()
    }

    /**
     * Downloads [url] to [dest], calling [progress] with (done, total) about
     * twice a second. Throws on failure after retries; the saved state lets
     * the next call resume.
     */
    suspend fun fetch(url: String, dest: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        var plan = probe(url)
        dest.parentFile?.mkdirs()
        val chunks: MutableList<Chunk> = (load(dest, plan.total)?.toMutableList() ?: run {
            discard(dest)
            RandomAccessFile(dest, "rw").use { it.setLength(plan.total) }
            val parts = when {
                !plan.ranged -> 1
                plan.total > 500L shl 20 -> 12
                plan.total > 50L shl 20 -> 6
                else -> 1
            }
            val size = plan.total / parts
            (0 until parts).map { i ->
                val start = i * size
                val end = if (i == parts - 1) plan.total - 1 else start + size - 1
                Chunk(start, end, 0)
            }.toMutableList()
        })
        save(dest, plan.total, chunks)
        val done = AtomicLong(chunks.sumOf { it.done })
        val lock = Any()

        coroutineScope {
            val reporter = launch {
                while (isActive) {
                    progress(done.get(), plan.total)
                    save(dest, plan.total, chunks)
                    delay(500)
                }
            }
            val workers = chunks.filter { !it.complete }.map { chunk ->
                launch {
                    var failures = 0
                    while (!chunk.complete) {
                        ensureActive()
                        try {
                            // Without range support a broken stream can only restart from zero.
                            if (!plan.ranged && chunk.done > 0) { done.addAndGet(-chunk.done); synchronized(chunks) { chunk.done = 0 } }
                            val from = chunk.start + chunk.done
                            val req = Request.Builder().url(plan.url).apply { if (plan.ranged) header("Range", "bytes=$from-${chunk.end}") }.build()
                            http.newCall(req).execute().use { r ->
                                if (r.code == 403 || r.code == 410) {
                                    // The signed CDN link expired: resolve a fresh one.
                                    synchronized(lock) { plan = probe(url) }
                                    throw IOException("link expired")
                                }
                                if (plan.ranged && r.code != 206) throw IOException("HTTP ${r.code}")
                                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                                RandomAccessFile(dest, "rw").use { raf ->
                                    val ch = raf.channel
                                    val input = r.body.byteStream()
                                    val buf = ByteArray(256 * 1024)
                                    var pos = from
                                    while (!chunk.complete) {
                                        val want = minOf(buf.size.toLong(), chunk.length - chunk.done).toInt()
                                        val n = input.read(buf, 0, want)
                                        if (n < 0) break
                                        val bb = ByteBuffer.wrap(buf, 0, n)
                                        while (bb.hasRemaining()) pos += ch.write(bb, pos)
                                        synchronized(chunks) { chunk.done += n }
                                        done.addAndGet(n.toLong())
                                    }
                                }
                            }
                            failures = 0
                        } catch (e: IOException) {
                            failures++
                            Log.w(TAG, "range ${chunk.start} failed ($failures): ${e.message}")
                            if (failures > 8) throw e
                            delay(1_000L * failures)
                        }
                    }
                }
            }
            workers.forEach { it.join() }
            reporter.cancel()
        }
        save(dest, plan.total, chunks)
        progress(plan.total, plan.total)
        if (chunks.any { !it.complete } || dest.length() != plan.total) throw IOException("download incomplete")
        stateFile(dest).delete()
    }
}
