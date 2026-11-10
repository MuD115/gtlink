package com.nmnd.gtlink.link

import com.nmnd.gtlink.protocol.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/** Something ready to send to the watch's file service. */
class UploadItem(
    /** The name the watch files it under; watch faces use "<id>_<version>". */
    val fileName: String,
    /** [Commands.FileType]. */
    val type: Int,
    val data: ByteArray,
    /** What the UI calls it. */
    val title: String,
    val artist: String? = null,
    val preview: ByteArray? = null,
    /** Watch faces only: the resolution they were made for, e.g. "466*466". */
    val screen: String? = null,
)

sealed interface UploadState {
    data object Idle : UploadState
    data class Running(val title: String, val percent: Int) : UploadState
    data class Done(val title: String) : UploadState
    data class Failed(val title: String, val reason: String) : UploadState
}

/**
 * Huawei's file push (service 0x28), port of Gadgetbridge's HuaweiUploadManager.
 *
 * The phone only announces the file; from then on the watch drives: it asks for the
 * SHA-256, negotiates chunk parameters, then repeatedly asks for the next chunk until
 * it reports the result. So this class is a state machine fed by watch messages.
 */
class FileUploader(private val log: ProtocolLog, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state

    private var item: UploadItem? = null
    private var sha256 = ByteArray(0)
    private var fileId = 0
    private var unitSize = 0
    private var encrypt = true
    private var watchdog: Job? = null

    val busy: Boolean get() = item != null

    /** The song currently being sent, for the watch's title/artist question afterwards. */
    var lastMusic: UploadItem? = null
        private set

    suspend fun start(link: WatchLink, upload: UploadItem) {
        if (item != null) throw WatchException("Another upload is still running")
        item = upload
        sha256 = Crypto.sha256(upload.data)
        if (upload.type == Commands.FileType.MUSIC) lastMusic = upload
        _state.value = UploadState.Running(upload.title, 0)
        log.add("upload start ${upload.fileName} (${upload.data.size} bytes, type ${upload.type})")
        link.send(Commands.fileInfo(upload.fileName, upload.data.size, upload.type))
        armWatchdog()
    }

    fun cancel(reason: String = "Cancelled") {
        val current = item ?: return
        finish(UploadState.Failed(current.title, reason))
    }

    /** Feeds a 0x28 message from the watch. Returns true when it belonged to the upload. */
    suspend fun onMessage(link: WatchLink, m: Incoming): Boolean {
        if (m.service != 0x28) return false
        val current = item ?: return true
        armWatchdog()
        val t = m.tlv
        when (m.command) {
            0x02 -> {
                val result = t.asInt(0x7F, RESULT_OK)
                if (result != RESULT_OK) finish(UploadState.Failed(current.title, describe(result)))
            }
            0x03 -> {
                fileId = t.asInt(0x01, 0)
                link.send(Commands.fileHash(fileId, sha256))
            }
            0x04 -> {
                fileId = t.asInt(0x01, fileId)
                unitSize = t.asInt(0x05, 0)
                val noEncrypt = t.asInt(0x09, 0) == 1
                encrypt = !noEncrypt
                log.add("upload params: unit $unitSize, encrypt $encrypt, resume at ${t.asInt(0x08, 0)}")
                link.send(Commands.fileConsultAck(fileId, noEncrypt))
            }
            0x05 -> {
                val position = t.asInt(0x02, 0)
                val size = t.asInt(0x03, 0)
                _state.value = UploadState.Running(current.title, (position * 100L / current.data.size.coerceAtLeast(1)).toInt())
                sendChunk(link, current.data, position, size)
            }
            0x07 -> {
                link.send(Commands.fileComplete(fileId))
                log.add("upload finished ${current.fileName}")
                finish(UploadState.Done(current.title))
            }
            0x08 -> {
                val code = t.asInt(0x7F, 0)
                finish(UploadState.Failed(current.title, describe(code)))
            }
        }
        return true
    }

    private suspend fun sendChunk(link: WatchLink, data: ByteArray, position: Int, size: Int) {
        val end = minOf(data.size, position + size)
        if (position >= end || unitSize <= 0) return
        var offset = position
        var index = 0
        while (offset < end) {
            val n = minOf(unitSize, end - offset)
            val body = ByteBuffer.allocate(6 + n)
                .put(fileId.toByte())
                .put(index.toByte())
                .putInt(offset)
                .put(data, offset, n)
                .array()
            link.sendRaw(0x28, 0x06, body, encrypt)
            offset += n
            index++
        }
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(45_000)
            item?.let { finish(UploadState.Failed(it.title, "The watch stopped responding")) }
        }
    }

    private fun finish(state: UploadState) {
        watchdog?.cancel()
        item = null
        _state.value = state
        if (state is UploadState.Failed) log.add("! upload failed: ${state.reason}")
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            140004 -> "Too many watch faces installed; delete one first"
            140008 -> "That file is already on the watch"
            140009 -> "Not enough space on the watch"
            else -> "The watch refused the file (code $code)"
        }
    }
}
