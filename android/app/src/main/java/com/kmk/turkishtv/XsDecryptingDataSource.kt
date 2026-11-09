package com.kmk.turkishtv

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bandwidth-diet data source for xtremestream (Turkish "OsmanOnlineLive"):
 * the addon's /xs/segment.ts now 302-redirects to an encrypted CDN file.
 * This source follows the redirect, fetches the bytes straight from the CDN,
 * decrypts AES-128-CBC on-device (IV = segment index from the URL), and
 * hands clear MPEG-TS to ExoPlayer. Video never transits the server.
 *
 * Non-xs URLs pass through to the default HTTP source untouched.
 */
class XsDecryptingDataSource(
    private val keyHexProvider: () -> String?,
    private val fallback: DataSource
) : BaseDataSource(true) {

    private var buffer: ByteArray? = null
    private var pos = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        opened = true
        val url = dataSpec.uri.toString()

        if (!url.contains("/xs/segment.ts")) {
            transferInitializing(dataSpec)
            return fallback.open(dataSpec)
        }

        transferInitializing(dataSpec)
        val idx = Uri.parse(url).getQueryParameter("i")?.toIntOrNull() ?: 0
        val keyHex = keyHexProvider()
            ?: throw IOException("xs key not captured yet (play the master first)")

        val conn = followRedirect(url)
        val enc = readAll(conn)
        val plain = decrypt(enc, idx, keyHex)
        buffer = plain
        pos = 0
        bytesTransferred(0) // register the spec
        return plain.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (this.buffer == null) return fallback.read(buffer, offset, length)
        val b = this.buffer!!
        if (pos >= b.size) return C_RESULT_END_OF_INPUT
        val n = minOf(length, b.size - pos)
        System.arraycopy(b, 0, buffer, offset, n)
        pos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = buffer?.let { null } ?: fallback.uri

    override fun close() {
        opened = false
        buffer = null
        pos = 0
        try { fallback.close() } catch (e: Exception) { }
    }

    // ---- helpers ----

    private fun followRedirect(url: String): HttpURLConnection {
        var current = url
        var hops = 0
        while (hops < 5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            conn.connect()
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("empty redirect")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                hops++
            } else {
                return conn
            }
        }
        throw IOException("too many redirects")
    }

    private fun readAll(conn: HttpURLConnection): ByteArray {
        return try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun decrypt(enc: ByteArray, index: Int, keyHex: String): ByteArray {
        val keyBytes = ByteArray(16)
        for (i in 0 until 16) {
            keyBytes[i] = ((Character.digit(keyHex[i * 2], 16) shl 4)
                or Character.digit(keyHex[i * 2 + 1], 16)).toByte()
        }
        val iv = ByteArray(16)
        val bb = java.nio.ByteBuffer.wrap(iv)
        bb.position(8)
        bb.putLong(index.toLong())

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(enc)
    }

    companion object {
        const val C_RESULT_END_OF_INPUT = -1
    }
}
