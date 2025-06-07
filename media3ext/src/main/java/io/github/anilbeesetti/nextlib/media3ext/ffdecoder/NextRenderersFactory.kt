package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener


@UnstableApi
open class NextRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @JvmInline
    value class Flags(private val value: Int) {
        companion object {
            val FLAG_ENABLE_HEVC = Flags(1)
            val FLAG_DISABLE_FFMPEG_AUDIO_DECODER = Flags(1 shl 1)
            // 更多 flag...
        }

        operator fun plus(other: Flags): Flags = Flags(this.value or other.value)

        operator fun contains(flag: Flags): Boolean = (this.value and flag.value) == flag.value
    }
    private var enabledFlags: Flags = Flags(0)
    fun setFlags(flag: Flags):NextRenderersFactory{
        this.enabledFlags = flag
        return this
    }
    fun addFlags(flag: Flags):NextRenderersFactory{
        this.enabledFlags +=flag
        return this
    }
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val audioRenderer =
            MediaCodecAudioRenderer(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink
            )
        out.add(audioRenderer)


        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF
            && Flags.FLAG_DISABLE_FFMPEG_AUDIO_DECODER in enabledFlags) return

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val renderer = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegAudioRenderer.")
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoRenderer =
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .build()
        out.add(videoRenderer)


        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val flag = if (Flags.FLAG_ENABLE_HEVC in enabledFlags) FfmpegVideoRenderer.FLAG_ENABLE_HEVC else 0
            val renderer = FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener
                , MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,flag)
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegVideoRenderer.")
        } catch (e: java.lang.Exception) {
            // The extension is present, but instantiation failed.
            throw java.lang.RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    companion object {
        const val TAG = "NextRenderersFactory"
        inline fun buildFlags(block: MutableList<Flags>.() -> Unit): Flags {
            val list = mutableListOf<Flags>()
            block(list)
            return list.fold(Flags(0)) { acc, flag -> acc + flag }
        }
    }
}
