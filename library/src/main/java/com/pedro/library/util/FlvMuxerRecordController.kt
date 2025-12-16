package com.pedro.library.util

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.BitrateManager
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.toUInt24
import com.pedro.common.toUInt32
import com.pedro.common.trySend
import com.pedro.encoder.video.VideoEncoderHelper
import com.pedro.library.base.recording.BaseRecordController
import com.pedro.library.base.recording.RecordController
import com.pedro.library.base.recording.RecordController.RecordTracks
import com.pedro.rtmp.amf.v0.AmfEcmaArray
import com.pedro.rtmp.amf.v0.AmfString
import com.pedro.rtmp.flv.BasePacket
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.audio.AudioFormat
import com.pedro.rtmp.flv.audio.packet.AacPacket
import com.pedro.rtmp.flv.audio.packet.G711Packet
import com.pedro.rtmp.flv.audio.packet.OpusPacket
import com.pedro.rtmp.flv.video.VideoFormat
import com.pedro.rtmp.flv.video.packet.Av1Packet
import com.pedro.rtmp.flv.video.packet.H264Packet
import com.pedro.rtmp.flv.video.packet.H265Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class FlvMuxerRecordController: BaseRecordController() {
    // Use a short consistent tag used by user for debugging
    private val logTag = "checkflv"
    private var outputStream: OutputStream? = null
    private var videoPacket: BasePacket? = null
    private var audioPacket: BasePacket? = null
    private val queue = LinkedBlockingQueue<MediaFrame>(200)
    private var job: Job? = null
    private var width = 0
    private var height = 0
    private var fps = 0
    private var sampleRate = 0
    private var isStereo = true
    private var sendInfo = false
    private var lastCsd0: ByteBuffer? = null
    private val preConfigBuffer = ArrayDeque<MediaFrame>()
    private val MAX_PRECONFIG = 50

    override fun startRecord(path: String, listener: RecordController.Listener?, tracks: RecordTracks) {
        Log.d(logTag, "startRecord 1 $path")
        this.tracks = tracks
        outputStream = FileOutputStream(path)
        start(listener)
    }

    override fun startRecord(fd: FileDescriptor, listener: RecordController.Listener?, tracks: RecordTracks) {
        Log.d(logTag, "startRecord 2")
        this.tracks = tracks
        outputStream = FileOutputStream(fd)
        start(listener)
    }

    private fun start(listener: RecordController.Listener?) {
        Log.d(logTag, "startRecord start listener $listener")
        audioPacket = when (audioCodec) {
            AudioCodec.G711 -> G711Packet()
            AudioCodec.AAC -> AacPacket().apply { sendAudioInfo(sampleRate, isStereo) }
            AudioCodec.OPUS -> OpusPacket().apply { sendAudioInfo(sampleRate, isStereo) }
        }
        videoPacket = when (videoCodec) {
            VideoCodec.H264 -> H264Packet()
            VideoCodec.H265 -> H265Packet()
            VideoCodec.AV1 -> Av1Packet()
        }
        this.listener = listener
        status = RecordController.Status.STARTED
        if (listener != null) {
            bitrateManager = BitrateManager(listener)
            listener.onStatusChange(status)
        } else {
            bitrateManager = null
        }
        queue.clear()
        outputStream?.let {
            try {
                it.write(createFlvFileHeader())
                writeFlvFileMetadata(it)
            } catch (_: Exception) {}
        }
        if (tracks == RecordTracks.AUDIO) status = RecordController.Status.RECORDING
        listener?.onStatusChange(status)
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val mediaFrame = runInterruptible { queue.take() }
                when (mediaFrame.type) {
                    MediaFrame.Type.VIDEO -> {
                        videoPacket?.createFlvPacket(mediaFrame) { packet ->
                            outputStream?.let { writeFlvPacket(it, packet) }
                        }
                    }
                    MediaFrame.Type.AUDIO -> {
                        audioPacket?.createFlvPacket(mediaFrame) { packet ->
                            outputStream?.let { writeFlvPacket(it, packet) }
                        }
                    }
                }
            }
        }
    }

    override fun stopRecord() {
        status = RecordController.Status.STOPPED
        runBlocking { job?.cancelAndJoin() }
        pauseMoment = 0
        pauseTime = 0
        startTs = 0
        // clear queued frames (they were already written or moved)
        queue.clear()
        videoPacket?.reset(false)
        audioPacket?.reset(false)
        try {
            outputStream?.close()
        } catch (_: Exception) { } finally {
            outputStream = null
        }
        requestKeyFrame = null
        sendInfo = false
        if (listener != null) listener.onStatusChange(status)
    }

    override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
        if (tracks != RecordTracks.AUDIO) {
            if (videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME || isKeyFrame(videoBuffer)) {
                if (!sendInfo) {
                    when (videoPacket) {
                        is H264Packet -> {
                            val buffers = VideoEncoderHelper.decodeSpsPpsFromBuffer(videoBuffer.duplicate(), videoInfo.size)
                            if (buffers != null) {
                                val oldSps = buffers.first
                                val oldPps = buffers.second
                                (videoPacket as H264Packet).sendVideoInfo(oldSps, oldPps)
                                sendInfo = true
                                Log.i("checkflv", "sendInfo=true codec=H264 from keyframe in recordVideo")
                            } else {
                                Log.e(logTag, "manual sps/pps extraction failed")
                            }
                        }
                        is H265Packet -> {
                            val byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(videoBuffer.duplicate())
                            if (byteBufferList.size == 3) {
                                val oldSps = byteBufferList[1]
                                val oldPps = byteBufferList[2]
                                val oldVps = byteBufferList[0]
                                (videoPacket as H265Packet).sendVideoInfo(oldSps, oldPps, oldVps)
                                sendInfo = true
                                Log.i("checkflv", "sendInfo=true codec=H265 from keyframe in recordVideo")
                            } else {
                                Log.e(logTag, "manual vps/sps/pps extraction failed")
                            }
                        }
                        is Av1Packet -> {
                            val obuSequence = VideoEncoderHelper.extractObuSequence(videoBuffer.duplicate(), videoInfo)
                            if (obuSequence != null) {
                                (videoPacket as Av1Packet).sendVideoInfo(obuSequence)
                                sendInfo = true
                                Log.i("checkflv", "sendInfo=true codec=AV1 from keyframe in recordVideo")
                            } else {
                                Log.e(logTag, "manual av1 extraction failed")
                            }
                        }
                    }
                }
                if (sendInfo && status == RecordController.Status.STARTED) {
                    requestKeyFrame = null
                    status = RecordController.Status.RECORDING
                    listener?.onStatusChange(status)
                }
            } else if (requestKeyFrame != null) {
                requestKeyFrame.onRequestKeyFrame()
                requestKeyFrame = null
            }

            if (status == RecordController.Status.RECORDING) {
                val frame = MediaFrame(videoBuffer.clone(), videoInfo.toMediaFrameInfo(), MediaFrame.Type.VIDEO)
                // If we haven't got codec params yet, buffer frames so they can be flushed after sendInfo=true
                if (!sendInfo) {
                    synchronized(preConfigBuffer) {
                        if (preConfigBuffer.size >= MAX_PRECONFIG) {
                            preConfigBuffer.removeFirst()
                            Log.w("checkflv", "preConfigBuffer full, dropping oldest frame")
                        }
                        preConfigBuffer.addLast(frame)
                        Log.v("checkflv", "buffering pre-config frame tsUs=${frame.info.timestamp} size=${frame.data.remaining()} bufCount=${preConfigBuffer.size}")
                    }
                } else {
                    queue.trySend(frame)
                    Log.v("checkflv", "recordVideo(RECORDING) enqueue tsUs=${videoInfo.presentationTimeUs} size=${videoInfo.size}")
                }
            }
        }
    }

    override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
        if (status == RecordController.Status.RECORDING && tracks != RecordTracks.VIDEO) {
            val frame = MediaFrame(audioBuffer.clone(), audioInfo.toMediaFrameInfo(), MediaFrame.Type.AUDIO)
            queue.trySend(frame)
        }
    }

    // Detect probable codec from the buffer's NAL unit headers. Returns VideoCodec.H264/H265 or null.
    private fun detectCodecFromBuffer(buffer: ByteBuffer): VideoCodec? {
        val dup = buffer.duplicate()
        dup.position(0)
        var i = 0
        // Try Annex-B (start-code) search first
        while (i < dup.remaining() - 3) {
            val b0 = dup.get(i).toInt() and 0xFF
            val b1 = dup.get(i + 1).toInt() and 0xFF
            val b2 = dup.get(i + 2).toInt() and 0xFF
            var startCodeLen = 0
            if (b0 == 0 && b1 == 0 && b2 == 0 && i + 3 < dup.remaining()) {
                val b3 = dup.get(i + 3).toInt() and 0xFF
                if (b3 == 1) startCodeLen = 4
            } else if (b0 == 0 && b1 == 0 && b2 == 1) {
                startCodeLen = 3
            }
            if (startCodeLen > 0) {
                val nalPos = i + startCodeLen
                if (nalPos < dup.remaining()) {
                    val nalByte = dup.get(nalPos).toInt() and 0xFF
                    val nalTypeH264 = nalByte and 0x1F
                    val nalTypeH265 = (nalByte shr 1) and 0x3F
                    // H.264 SPS/PPS types
                    if (nalTypeH264 == 7 || nalTypeH264 == 8) return VideoCodec.H264
                    // H.265 VPS/SPS/PPS types
                    if (nalTypeH265 in 32..35) return VideoCodec.H265
                    // If we find a primary IDR/BLA/CRA in h265 range, assume H265
                    if (nalTypeH265 in 16..23) return VideoCodec.H265
                    // If we find H.264 slice types, prefer H264
                    if (nalTypeH264 in 1..5) return VideoCodec.H264
                }
                i += startCodeLen
            } else {
                i++
            }
        }
        // If no start codes found, try length-prefixed (common from MediaCodec)
        try {
            val dup2 = buffer.duplicate()
            dup2.position(0)
            if (dup2.remaining() >= 4) {
                val len = dup2.int // reads 4 bytes as big-endian length
                if (len > 0 && dup2.remaining() >= len) {
                    val nalByte = dup2.get().toInt() and 0xFF
                    val nalTypeH264 = nalByte and 0x1F
                    val nalTypeH265 = (nalByte shr 1) and 0x3F
                    if (nalTypeH264 == 7 || nalTypeH264 == 8) return VideoCodec.H264
                    if (nalTypeH265 in 32..35) return VideoCodec.H265
                    if (nalTypeH265 in 16..23) return VideoCodec.H265
                    if (nalTypeH264 in 1..5) return VideoCodec.H264
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // Try to extract H.265 VPS/SPS/PPS from any buffer (Annex-B or length-prefixed). Returns list [vps,sps,pps] or empty.
    private fun extractH265ParamSets(buffer: ByteBuffer): List<ByteBuffer> {
        try {
            val result = VideoEncoderHelper.extractVpsSpsPpsFromH265(buffer.duplicate())
            if (result.size == 3) return result
        } catch (_: Exception) {}
        // Manual fallback: scan for start codes and pull NAL units
        val dup = buffer.duplicate()
        dup.position(0)
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val found = mutableMapOf<Int, ByteArray>() // nalType -> data
        var i = 0
        while (i < bytes.size - 4) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                val nalStart = i + 4
                var j = nalStart
                while (j < bytes.size - 3) {
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 0.toByte() && bytes[j + 3] == 1.toByte()) break
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) break
                    j++
                }
                val nal = bytes.copyOfRange(nalStart, if (j < bytes.size - 3) j else bytes.size)
                if (nal.isNotEmpty()) {
                    val nalHeader = nal[0].toInt() and 0xFF
                    val nalType = (nalHeader shr 1) and 0x3F
                    if (nalType in 32..34) {
                        found[nalType] = nal
                    }
                }
                i = j
            } else if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                val nalStart = i + 3
                var j = nalStart
                while (j < bytes.size - 3) {
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 0.toByte() && bytes[j + 3] == 1.toByte()) break
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) break
                    j++
                }
                val nal = bytes.copyOfRange(nalStart, if (j < bytes.size - 3) j else bytes.size)
                if (nal.isNotEmpty()) {
                    val nalHeader = nal[0].toInt() and 0xFF
                    val nalType = (nalHeader shr 1) and 0x3F
                    if (nalType in 32..34) {
                        found[nalType] = nal
                    }
                }
                i = j
            } else {
                i++
            }
        }
        if (found.containsKey(32) && found.containsKey(33) && found.containsKey(34)) {
            val vpsBytes = found[32] ?: return emptyList()
            val spsBytes = found[33] ?: return emptyList()
            val ppsBytes = found[34] ?: return emptyList()
            return listOf(ByteBuffer.wrap(vpsBytes), ByteBuffer.wrap(spsBytes), ByteBuffer.wrap(ppsBytes))
        }
        // length prefixed attempt
        try {
            val dup2 = buffer.duplicate()
            dup2.position(0)
            while (dup2.remaining() > 4) {
                val len = dup2.int
                if (len <= 0 || dup2.remaining() < len) break
                val nal = ByteArray(len)
                dup2.get(nal)
                val nalType = ((nal[0].toInt() and 0xFF) shr 1) and 0x3F
                if (nalType in 32..34) found[nalType] = nal
            }
            if (found.containsKey(32) && found.containsKey(33) && found.containsKey(34)) {
                val vpsBytes = found[32] ?: return emptyList()
                val spsBytes = found[33] ?: return emptyList()
                val ppsBytes = found[34] ?: return emptyList()
                return listOf(ByteBuffer.wrap(vpsBytes), ByteBuffer.wrap(spsBytes), ByteBuffer.wrap(ppsBytes))
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    // Try to extract H.264 SPS/PPS from buffer (Annex-B or length-prefixed). Returns Pair(sps,pps) or null.
    private fun extractH264ParamSets(buffer: ByteBuffer): Pair<ByteBuffer, ByteBuffer>? {
        try {
            val res = VideoEncoderHelper.decodeSpsPpsFromBuffer(buffer.duplicate(), buffer.remaining())
            if (res != null) {
                // VideoEncoderHelper returns an android.util.Pair; convert to kotlin.Pair
                return Pair(res.first as ByteBuffer, res.second as ByteBuffer)
            }
        } catch (_: Exception) {}
        val dup = buffer.duplicate()
        dup.position(0)
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        while (i < bytes.size - 4) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                val nalStart = i + 4
                var j = nalStart
                while (j < bytes.size - 3) {
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 0.toByte() && bytes[j + 3] == 1.toByte()) break
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) break
                    j++
                }
                val nal = bytes.copyOfRange(nalStart, if (j < bytes.size - 3) j else bytes.size)
                if (nal.isNotEmpty()) {
                    val nalType = nal[0].toInt() and 0x1F
                    if (nalType == 7) sps = nal
                    if (nalType == 8) pps = nal
                }
                i = j
            } else if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                val nalStart = i + 3
                var j = nalStart
                while (j < bytes.size - 3) {
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 0.toByte() && bytes[j + 3] == 1.toByte()) break
                    if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) break
                    j++
                }
                val nal = bytes.copyOfRange(nalStart, if (j < bytes.size - 3) j else bytes.size)
                if (nal.isNotEmpty()) {
                    val nalType = nal[0].toInt() and 0x1F
                    if (nalType == 7) sps = nal
                    if (nalType == 8) pps = nal
                }
                i = j
            } else {
                i++
            }
        }
        if (sps != null && pps != null) return Pair(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))
        // length-prefixed
        try {
            val dup2 = buffer.duplicate()
            dup2.position(0)
            while (dup2.remaining() > 4) {
                val len = dup2.int
                if (len <= 0 || dup2.remaining() < len) break
                val nal = ByteArray(len)
                dup2.get(nal)
                val nalType = nal[0].toInt() and 0x1F
                if (nalType == 7) sps = nal
                if (nalType == 8) pps = nal
                if (sps != null && pps != null) break
            }
            if (sps != null && pps != null) return Pair(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))
        } catch (_: Exception) {}
        return null
    }

    // Parse hvcC formatted csd-0 (ISO/IEC 14496-15) to extract arrays of NAL units (VPS/SPS/PPS)
    // Returns list [vps, sps, pps] when present, otherwise emptyList()
    private fun parseHvcC(buffer: ByteBuffer): List<ByteBuffer> {
        try {
            val dup = buffer.duplicate()
            dup.position(0)
            val total = dup.remaining()
            if (total < 23) return emptyList()
            // numOfArrays is at byte index 22 (zero-based)
            val numOfArrays = dup.get(22).toInt() and 0xFF
            var offset = 23
            val found = mutableMapOf<Int, ByteArray>()
            repeat(numOfArrays) {
                if (offset + 3 > total) return@repeat
                // arrayCompletenessAndType byte present, but we only need the subsequent numNalus
                dup.get(offset) // consume the byte
                val numNalus = ((dup.get(offset + 1).toInt() and 0xFF) shl 8) or (dup.get(offset + 2).toInt() and 0xFF)
                offset += 3
                repeat(numNalus) {
                    if (offset + 2 > total) return@repeat
                    val nalUnitLength = ((dup.get(offset).toInt() and 0xFF) shl 8) or (dup.get(offset + 1).toInt() and 0xFF)
                    offset += 2
                    if (offset + nalUnitLength > total) return@repeat
                    val nal = ByteArray(nalUnitLength)
                    dup.position(offset)
                    dup.get(nal)
                    offset += nalUnitLength
                    if (nal.isNotEmpty()) {
                        val nalHeader = nal[0].toInt() and 0xFF
                        val nalType = (nalHeader shr 1) and 0x3F
                        if (nalType in 32..34) {
                            found[nalType] = nal
                        }
                    }
                }
            }
            if (found.containsKey(32) && found.containsKey(33) && found.containsKey(34)) {
                return listOf(ByteBuffer.wrap(found[32]!!), ByteBuffer.wrap(found[33]!!), ByteBuffer.wrap(found[34]!!))
            }
        } catch (_: Exception) { }
        return emptyList()
    }

    private fun getVideoInfo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val detected = detectCodecFromBuffer(buffer)
        when {
            detected == VideoCodec.H264 && videoPacket !is H264Packet -> {
                Log.w(logTag, "Runtime codec mismatch detected: incoming buffer looks like H.264 but controller expected ${videoPacket?.javaClass?.simpleName} - switching packets")
                videoPacket = H264Packet()
                videoCodec = VideoCodec.H264
                lastCsd0?.duplicate()?.let { csd ->
                    runCatching { VideoEncoderHelper.decodeSpsPpsFromBuffer(csd, csd.remaining()) }
                        .getOrNull()?.let { buffers ->
                            (videoPacket as H264Packet).sendVideoInfo(buffers.first, buffers.second)
                            sendInfo = true
                            Log.i(logTag, "Configured H264Packet from lastCsd0")
                            runCatching { outputStream?.let { writeFlvFileMetadata(it) } }
                        }
                }
            }
            detected == VideoCodec.H265 && videoPacket !is H265Packet -> {
                Log.w(logTag, "Runtime codec mismatch detected: incoming buffer looks like H.265 but controller expected ${videoPacket?.javaClass?.simpleName} - switching packets")
                videoPacket = H265Packet()
                videoCodec = VideoCodec.H265
                lastCsd0?.duplicate()?.let { csd ->
                    runCatching { VideoEncoderHelper.extractVpsSpsPpsFromH265(csd) }
                        .getOrNull()?.takeIf { it.size == 3 }?.let { list ->
                            (videoPacket as H265Packet).sendVideoInfo(list[1], list[2], list[0])
                            sendInfo = true
                            Log.i(logTag, "Configured H265Packet from lastCsd0")
                            runCatching { outputStream?.let { writeFlvFileMetadata(it) } }
                        }
                }
            }
        }
        // Check for CODEC_CONFIG buffer first (this contains SPS/PPS/VPS for H.265)
        val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyByFlag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        } else {
            false
        }
        val isKeyByNal = isKeyFrame(buffer)
        Log.d(logTag, "getVideoInfo flags=${info.flags} isCodecConfig=$isCodecConfig isKeyByFlag=$isKeyByFlag isKeyByNal=$isKeyByNal sendInfo=$sendInfo status=$status size=${info.size}")

        // Handle CODEC_CONFIG buffer specially - this is where we get the real codec parameters
        if (isCodecConfig && !sendInfo) {
            Log.i(logTag, "Processing CODEC_CONFIG buffer size=${info.size}")
            when (videoPacket) {
                is H264Packet -> {
                    try {
                        val safeBuf = prepareBufferForParsing(buffer, info.size)
                        val buffers = VideoEncoderHelper.decodeSpsPpsFromBuffer(safeBuf, info.size)
                        if (buffers != null) {
                            Log.i(logTag, "✅ H264: Extracted SPS/PPS from CODEC_CONFIG buffer")
                            val oldSps = buffers.first
                            val oldPps = buffers.second
                            (videoPacket as H264Packet).sendVideoInfo(oldSps, oldPps)
                            sendInfo = true
                        } else {
                            Log.w(logTag, "❌ H264: Failed to extract SPS/PPS from CODEC_CONFIG buffer")
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Exception parsing H264 CODEC_CONFIG: ${e.message}")
                    }
                }
                is H265Packet -> {
                    try {
                        val safeBuf = prepareBufferForParsing(buffer, info.size)
                        val byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(safeBuf)
                        if (byteBufferList.size == 3) {
                            Log.i(logTag, "✅ H265: Extracted VPS/SPS/PPS from CODEC_CONFIG buffer")
                            val oldSps = byteBufferList[1]
                            val oldPps = byteBufferList[2]
                            val oldVps = byteBufferList[0]
                            (videoPacket as H265Packet).sendVideoInfo(oldSps, oldPps, oldVps)
                            sendInfo = true
                        } else {
                            Log.e(logTag, "❌ H265: Failed to extract VPS/SPS/PPS from CODEC_CONFIG buffer")
                            try {
                                val previewSize = minOf(64, info.size)
                                val dup = buffer.duplicate()
                                val bytes = ByteArray(previewSize)
                                dup.position(0)
                                dup.get(bytes, 0, previewSize)
                                val hex = bytes.joinToString(separator = " ") { String.format("%02X", it) }
                                Log.e(logTag, "CODEC_CONFIG buffer preview: $hex")
                            } catch (e: Exception) {
                                Log.e(logTag, "Failed to create preview: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Exception parsing H265 CODEC_CONFIG: ${e.message}")
                    }
                }
                is Av1Packet -> {
                    val obuSequence = VideoEncoderHelper.extractObuSequence(buffer.duplicate(), info)
                    if (obuSequence != null) {
                        Log.i(logTag, "✅ AV1: Extracted config from CODEC_CONFIG buffer")
                        (videoPacket as Av1Packet).sendVideoInfo(obuSequence)
                        sendInfo = true
                    } else {
                        Log.w(logTag, "❌ AV1: Failed to extract from CODEC_CONFIG buffer")
                    }
                }
                else -> {
                    Log.e(logTag, "Unsupported codec: ${videoPacket?.javaClass?.name ?: "null"}")
                }
            }
            // Don't queue CODEC_CONFIG buffers - they're just metadata
            return
        }

        // If EITHER flag or NAL detection says it's a keyframe, treat it as one
        if (isKeyByFlag || isKeyByNal) {
            // Only attempt manual extraction once; if it fails and we already tried, don't spam logs.
            if (!sendInfo) {
                when (videoPacket) {
                    is H264Packet -> {
                        try {
                            val safeBuf = prepareBufferForParsing(buffer, info.size)
                            val buffers = VideoEncoderHelper.decodeSpsPpsFromBuffer(safeBuf, info.size)
                            if (buffers != null) {
                                Log.i(logTag, "manual sps/pps extraction success")
                                val oldSps = buffers.first
                                val oldPps = buffers.second
                                (videoPacket as H264Packet).sendVideoInfo(oldSps, oldPps)
                                sendInfo = true
                            } else {
                                Log.w(logTag, "manual sps/pps extraction failed; will rely on csd-0/csd-1 if available")
                            }
                        } catch (e: Exception) {
                            Log.e(logTag, "Exception during manual H264 extraction: ${e.message}")
                        }
                    }
                    is H265Packet -> {
                        try {
                            val safeBuf = prepareBufferForParsing(buffer, info.size)
                            val byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(safeBuf)
                            if (byteBufferList.size == 3) {
                                Log.i(logTag, "✅ H265: Extracted VPS/SPS/PPS from keyframe (fallback method)")
                                val oldSps = byteBufferList[1]
                                val oldPps = byteBufferList[2]
                                val oldVps = byteBufferList[0]
                                (videoPacket as H265Packet).sendVideoInfo(oldSps, oldPps, oldVps)
                                sendInfo = true
                            } else {
                                Log.w(logTag, "❌ H265: Failed to extract VPS/SPS/PPS from keyframe (this is expected if CODEC_CONFIG was already processed)")
                            }
                        } catch (e: Exception) {
                            Log.e(logTag, "Exception during manual H265 extraction: ${e.message}")
                        }
                    }
                    is Av1Packet -> {
                        val obuSequence = VideoEncoderHelper.extractObuSequence(buffer.duplicate(), info)
                        if (obuSequence != null) {
                            (videoPacket as Av1Packet).sendVideoInfo(obuSequence)
                            sendInfo = true
                        } else {
                            Log.w(logTag, "manual av1 extraction failed; will rely on csd-0 if available")
                        }
                    }
                    else -> {
                        Log.e(logTag, "Unsupported codec: ${videoPacket?.javaClass?.name ?: "null"}")
                    }
                }
            }
            if (sendInfo && status == RecordController.Status.STARTED) {
                requestKeyFrame = null
                status = RecordController.Status.RECORDING
                listener?.onStatusChange(status)
                // flush any frames buffered while waiting for config
                synchronized(preConfigBuffer) {
                    val flushCount = preConfigBuffer.size
                    Log.i("checkflv", "sendInfo=true flush buffered frames=$flushCount")
                    while (preConfigBuffer.isNotEmpty()) {
                        val f = preConfigBuffer.removeFirst()
                        queue.trySend(f)
                    }
                }
            }
        } else if (requestKeyFrame != null) {
            requestKeyFrame.onRequestKeyFrame()
            requestKeyFrame = null
        }
    }

    override fun setVideoFormat(videoFormat: MediaFormat) {
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        this.width = width
        this.height = height
        this.fps = fps
        when (videoPacket) {
            is H264Packet -> {
                val sps = videoFormat.getByteBuffer("csd-0")
                val pps = videoFormat.getByteBuffer("csd-1")
                if (sps != null && pps != null) {
                    (videoPacket as H264Packet).sendVideoInfo(sps.duplicate(), pps.duplicate())
                    sendInfo = true
                    Log.d("checkflv", "setVideoFormat sendInfo=true codec=H264 status=$status")
                    // store csd0 for future fallback
                    try { lastCsd0 = sps.duplicate() } catch (_: Exception) {}
                }
            }
            is H265Packet -> {
                val bufferInfo = videoFormat.getByteBuffer("csd-0")
                if (bufferInfo != null) {
                    // store csd0 for fallback use
                    try { lastCsd0 = bufferInfo.duplicate() } catch (_: Exception) {}
                    // First, validate that csd-0 contains H.265 data, not H.264
                    val dup = bufferInfo.duplicate()
                    if (dup.remaining() > 4) {
                        var nalType = -1
                        if (dup.get(0).toInt() == 0 && dup.get(1).toInt() == 0 && dup.get(2).toInt() == 0 && dup.get(3).toInt() == 1) {
                            nalType = (dup.get(4).toInt() and 0x7E) shr 1
                        } else if (dup.get(0).toInt() == 0 && dup.get(1).toInt() == 0 && dup.get(2).toInt() == 1) {
                            nalType = (dup.get(3).toInt() and 0x7E) shr 1
                        }
                        if (nalType == 7 || nalType == 8) {
                            Log.e(logTag, "❌ CODEC MISMATCH: csd-0 contains H.264 data (NAL type $nalType) but codec is set to H.265!")
                            Log.e(logTag, "❌ This will likely prevent H.265 video from being recorded.")
                            // switch to H264 to attempt to recover
                            videoPacket = H264Packet()
                            videoCodec = VideoCodec.H264
                            try {
                                val h264 = extractH264ParamSets(bufferInfo.duplicate())
                                if (h264 != null) {
                                    (videoPacket as H264Packet).sendVideoInfo(h264.first, h264.second)
                                    sendInfo = true
                                    Log.i(logTag, "Recovered by switching to H.264 from csd-0")
                                }
                            } catch (e: Exception) {
                                Log.w(logTag, "Failed to recover H.264 from csd-0: ${e.message}")
                            }
                        }
                    }
                    // Try typical extractor first, using a safe buffer view
                    val safeCsd = prepareBufferForParsing(bufferInfo, bufferInfo.remaining())
                    var byteBufferList = try {
                        VideoEncoderHelper.extractVpsSpsPpsFromH265(safeCsd)
                    } catch (_: Exception) { null }
                    if (byteBufferList == null || byteBufferList.size != 3) {
                        // try hvcC parser fallback
                        byteBufferList = try { parseHvcC(bufferInfo.duplicate()) } catch (_: Exception) { emptyList() }
                    }
                    if (byteBufferList.size == 3) {
                        val sps = byteBufferList[1]
                        val pps = byteBufferList[2]
                        val vps = byteBufferList[0]
                        (videoPacket as H265Packet).sendVideoInfo(sps, pps, vps)
                        sendInfo = true
                        Log.d("checkflv", "setVideoFormat sendInfo=true codec=H265 status=$status")
                    } else {
                        Log.w(logTag, "csd-0 vps/sps/pps extraction failed; will attempt manual extraction on keyframe")
                    }
                }
            }
            is Av1Packet -> {
                val bufferInfo = videoFormat.getByteBuffer("csd-0")
                if (bufferInfo != null && bufferInfo.remaining() > 4) {
                    (videoPacket as Av1Packet).sendVideoInfo(bufferInfo.duplicate())
                    sendInfo = true
                    try { lastCsd0 = bufferInfo.duplicate() } catch (_: Exception) {}
                }
            }
        }
        if (sendInfo && status == RecordController.Status.STARTED) {
            // update metadata now that we know format
            try {
                outputStream?.let { writeFlvFileMetadata(it) }
            } catch (_: Exception) {}
        }
    }

    override fun setAudioFormat(audioFormat: MediaFormat) {
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        this.sampleRate = sampleRate
        this.isStereo = channels > 1
        (audioPacket as? AacPacket)?.sendAudioInfo(sampleRate, isStereo)
    }

    override fun resetFormats() {
    }

    private fun createFlvFileHeader(): ByteArray {
        val flag: Byte = if (tracks == RecordTracks.AUDIO) 0x04 else if (tracks == RecordTracks.VIDEO) 0x01 else 0x05
        return byteArrayOf(
            0x46, 0x4C, 0x56, // "FLV"
            0x01, // Versión 1
            flag,
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00
        )
    }

    private fun writeFlvFileMetadata(outputStream: OutputStream) {
        val head = AmfString("onMetaData")
        val info = AmfEcmaArray()
        info.setProperty("width", width.toDouble())
        info.setProperty("height", height.toDouble())
        val videoCodecValue = when (videoCodec) {
            VideoCodec.H264 -> VideoFormat.AVC.value
            VideoCodec.H265 -> VideoFormat.HEVC.value
            VideoCodec.AV1 -> VideoFormat.AV1.value
            else -> throw IllegalArgumentException("unsupported null codec")
        }
        info.setProperty("videocodecid", videoCodecValue.toDouble())
        info.setProperty("framerate", fps.toDouble())
        val audioCodecValue = when (audioCodec) {
            AudioCodec.AAC -> AudioFormat.AAC.value
            AudioCodec.G711 -> AudioFormat.G711_A.value
            AudioCodec.OPUS -> AudioFormat.OPUS.value
        }
        info.setProperty("audiocodecid", audioCodecValue.toDouble())
        info.setProperty("audiosamplerate", sampleRate.toDouble())
        info.setProperty("audiosamplesize", 16.0)
        info.setProperty("stereo", isStereo)

        val output = ByteArrayOutputStream()
        head.writeHeader(output)
        head.writeBody(output)
        info.writeHeader(output)
        info.writeBody(output)

        val data = output.toByteArray()
        val flvHeaderTag = createHeaderTag(0x12, data.size, 0)
        val flvTagSize = (flvHeaderTag.size + data.size).toUInt32()

        try {
            outputStream.write(flvHeaderTag)
            outputStream.write(data)
            outputStream.write(flvTagSize)
        } catch (_: Exception) {}
    }

    private fun writeFlvPacket(outputStream: OutputStream, flvPacket: FlvPacket) {
        val type: Byte = when (flvPacket.type) {
            FlvType.AUDIO -> 0x08
            FlvType.VIDEO -> 0x09
        }
        val flvHeaderTag = createHeaderTag(type, flvPacket.length, flvPacket.timeStamp)
        val flvTagSize = (flvHeaderTag.size + flvPacket.length).toUInt32()

        try {
            outputStream.write(flvHeaderTag)
            outputStream.write(flvPacket.buffer)
            outputStream.write(flvTagSize)
        } catch (_: Exception) {}
    }

    private fun createHeaderTag(type: Byte, length: Int, timeStamp: Long): ByteArray {
        return byteArrayOf(type)
            .plus(length.toUInt24())
            .plus(timeStamp.toInt().toUInt24())
            .plus((timeStamp shr 24).toByte())
            .plus(byteArrayOf(0x00, 0x00, 0x00))
    }

    // Create a safe duplicate limited to `size` bytes starting at position 0
    private fun prepareBufferForParsing(buffer: ByteBuffer, size: Int): ByteBuffer {
        val dup = buffer.duplicate()
        try {
            dup.position(0)
        } catch (_: Exception) { /* ignore */ }
        val available = dup.remaining()
        val toRead = if (size <= 0) available else minOf(size, available)
        try {
            dup.limit(dup.position() + toRead)
        } catch (_: Exception) { /* ignore */ }
        return dup
    }
}
