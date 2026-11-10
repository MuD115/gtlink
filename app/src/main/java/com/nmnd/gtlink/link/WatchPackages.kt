package com.nmnd.gtlink.link

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.Random
import java.util.zip.ZipInputStream

class PackageException(message: String) : Exception(message)

/**
 * Turns files the user picks into [UploadItem]s, following Gadgetbridge's HuaweiFwHelper:
 *  - watch face (.hwt): a zip with description.xml and com.huawei.watchface, itself a zip
 *    holding watchface.bin (older faces store the bin directly);
 *  - watch app (.bin): Huawei's "0xBE" container, package name first;
 *  - music: sent as-is, the watch decodes it.
 */
object WatchPackages {
    private val audioExtensions = setOf("mp3", "aac", "m4a", "wav", "flac", "ogg", "amr")

    fun prepare(displayName: String, bytes: ByteArray, mimeType: String?, audioTitle: String?, audioArtist: String?): UploadItem {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mimeType?.startsWith("audio/") == true || ext in audioExtensions -> music(displayName, bytes, audioTitle, audioArtist)
            bytes.isNotEmpty() && bytes[0] == 0xBE.toByte() -> app(bytes)
            isZip(bytes) -> watchface(bytes)
            else -> throw PackageException("Not a watch face (.hwt), watch app (.bin) or audio file")
        }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    data class WatchfaceInfo(val title: String, val version: String, val screen: String, val honor: Boolean)

    fun watchface(bytes: ByteArray): UploadItem {
        val entries = unzip(bytes)
        val xmlBytes = entries["description.xml"] ?: throw PackageException("No description.xml: not a Huawei watch face")
        val info = describe(String(stripBom(xmlBytes), Charsets.UTF_8))
        val inner = entries[if (info.honor) "com.honor.watchface" else "com.huawei.watchface"]
            ?: throw PackageException("Watch face package has no face data")
        val bin = if (isZip(inner)) unzip(inner)["watchface.bin"] ?: inner else inner
        return UploadItem(
            fileName = randomWatchfaceName(),
            type = Commands.FileType.WATCHFACE,
            data = bin,
            title = info.title.ifBlank { "Watch face" },
            preview = entries["preview/cover.jpg"],
            screen = info.screen.ifBlank { null },
        )
    }

    fun describe(xml: String): WatchfaceInfo {
        fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        return WatchfaceInfo(tag("title"), tag("version"), tag("screen"), honor = xml.contains("<HnTheme"))
    }

    /** Same scheme as Gadgetbridge: nine random digits and a version. */
    fun randomWatchfaceName(): String = String.format(Locale.ROOT, "%09d_1.0.0", Random().nextInt(1_000_000_000))

    fun app(bytes: ByteArray): UploadItem {
        val buf = ByteBuffer.wrap(bytes)
        if (buf.get() != 0xBE.toByte()) throw PackageException("Not a Huawei watch app")
        val len = buf.int
        if (len <= 0 || len > buf.remaining()) throw PackageException("Corrupt watch app header")
        val name = ByteArray(len).also { buf.get(it) }.toString(Charsets.UTF_8)
        return UploadItem(fileName = name + "_INSTALL", type = Commands.FileType.APP, data = bytes, title = name)
    }

    fun music(displayName: String, bytes: ByteArray, title: String?, artist: String?) = UploadItem(
        fileName = displayName,
        type = Commands.FileType.MUSIC,
        data = bytes,
        title = title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.'),
        artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown",
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) out[e.name] = zip.readBytes()
                }
            }
        } catch (e: Exception) {
            throw PackageException("Couldn't open the package: ${e.message}")
        }
        return out
    }

    private fun stripBom(b: ByteArray) =
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b.copyOfRange(3, b.size) else b
}
