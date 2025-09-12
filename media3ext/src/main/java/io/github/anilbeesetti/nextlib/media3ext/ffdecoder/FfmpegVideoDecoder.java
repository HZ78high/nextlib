package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.os.Build;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder
        implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    private static final String TAG = "FfmpegVideoDecoder";

    // LINT.IfChange
    private static final int VIDEO_DECODER_DROP_FRAME = 1;
    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_NEED_MORE_FRAME = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    private static final int VIDEO_DECODER_ERROR_READ_FRAME = -3;
    private static final int VIDEO_DECODER_ERROR_INVAILD_DATA = -4;
    // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

    private final String codecName;
    private long nativeContext;
    @Nullable
    private final byte[] extraData;
    private Format format;
    private int degree;

    @C.VideoOutputMode
    private volatile int outputMode;
    private final Thread decodeThread;

    private final Object lock;

    @GuardedBy("lock")
    private final ArrayDeque<DecoderInputBuffer> queuedInputBuffers;

    @GuardedBy("lock")
    private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputBuffers;

    @GuardedBy("lock")
    private final DecoderInputBuffer[] availableInputBuffers;

    @GuardedBy("lock")
    private final VideoDecoderOutputBuffer[] availableOutputBuffers;
    @GuardedBy("lock")
    private int availableInputBufferCount;

    @GuardedBy("lock")
    private int availableOutputBufferCount;

    @GuardedBy("lock")
    @Nullable
    private DecoderInputBuffer dequeuedInputBuffer;

    @GuardedBy("lock")
    @Nullable
    private FfmpegDecoderException exception;

    @GuardedBy("lock")
    private boolean flushed;

    @GuardedBy("lock")
    private boolean released;

    @GuardedBy("lock")
    private int skippedOutputBufferCount;

    @GuardedBy("lock")
    private long outputStartTimeUs;
    @GuardedBy("lock")
    @Nullable
    private DecoderInputBuffer stashInput;
    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads libgav1 will use to decode.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        lock = new Object();
        outputStartTimeUs = C.TIME_UNSET;
        queuedInputBuffers = new ArrayDeque<>();
        queuedOutputBuffers = new ArrayDeque<>();
        availableInputBuffers = new DecoderInputBuffer[numInputBuffers];
        availableInputBufferCount = numInputBuffers;
        for (int i = 0; i < availableInputBufferCount; i++) {
            availableInputBuffers[i] =
                    new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
            availableInputBuffers[i].ensureSpaceForWrite(initialInputBufferSize);
        }
        availableOutputBuffers = new VideoDecoderOutputBuffer[numOutputBuffers];
        availableOutputBufferCount = numOutputBuffers;
        for (int i = 0; i < availableOutputBufferCount; i++) {
            availableOutputBuffers[i] = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
        }
        assert format.sampleMimeType != null;
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
        extraData = getExtraData(format.sampleMimeType, format.initializationData);
        this.format = format;
        this.degree = format.rotationDegrees;
        decodeThread =
                new Thread("ExoPlayer:FfmpegVideoDecoder") {
                    @Override
                    public void run() {
                        FfmpegVideoDecoder.this.nativeContext =
                                ffmpegInitialize(codecName, extraData, threads, degree);
                        if (nativeContext == 0) {
                            synchronized (lock) {
                                FfmpegVideoDecoder.this.exception =
                                        new FfmpegDecoderException(
                                                "Failed to initialize decoder. Error: ");
                            }
                            ffmpegRelease(FfmpegVideoDecoder.this.nativeContext);
                            return;
                        }
                        FfmpegVideoDecoder.this.run();
                        ffmpegRelease(FfmpegVideoDecoder.this.nativeContext);
                    }
                };
        decodeThread.start();
        maybeThrowException();
    }

    /**
     * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
     * not required.
     */
    @Nullable
    private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
        if (initializationData.isEmpty()) return null;
        switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> {
                byte[] sps = initializationData.get(0);
                byte[] pps = initializationData.get(1);
                byte[] extraData = new byte[sps.length + pps.length];
                System.arraycopy(sps, 0, extraData, 0, sps.length);
                System.arraycopy(pps, 0, extraData, sps.length, pps.length);
                return extraData;
            }
            case MimeTypes.VIDEO_H265 -> {
                return initializationData.get(0);
            }
            default -> {
                // 通用处理，拼接所有数据
                int size = 0;
                for (byte[] data : initializationData) size += data.length;
                byte[] extra = new byte[size];
                int offset = 0;
                for (byte[] data : initializationData) {
                    System.arraycopy(data, 0, extra, offset, data.length);
                    offset += data.length;
                }
                return extra;
            }
        }
    }

    @Override
    public String getName() {
        return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
    }

    @Override
    @Nullable
    public final DecoderInputBuffer dequeueInputBuffer() throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            Assertions.checkState(dequeuedInputBuffer == null || flushed);
            dequeuedInputBuffer =
                    availableInputBufferCount == 0 || flushed
                            ? null
                            : availableInputBuffers[--availableInputBufferCount];
            return dequeuedInputBuffer;
        }
    }

    @Override
    public final void queueInputBuffer(DecoderInputBuffer inputBuffer) throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            Assertions.checkArgument(inputBuffer == dequeuedInputBuffer);
            queuedInputBuffers.addLast(inputBuffer);
            maybeNotifyDecodeLoop();
            dequeuedInputBuffer = null;
        }
    }

    @Override
    @Nullable
    public final VideoDecoderOutputBuffer dequeueOutputBuffer() throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            if (queuedOutputBuffers.isEmpty() || flushed) {
                return null;
            }
            return queuedOutputBuffers.removeFirst();
        }
    }

    @Override
    public final void flush() {
        synchronized (lock) {
            flushed = true;
            lock.notify();
        }
    }

    @Override
    public final void setOutputStartTimeUs(long outputStartTimeUs) {
        synchronized (lock) {
            this.outputStartTimeUs = outputStartTimeUs;
        }
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.M)
    public void release() {
        synchronized (lock) {
            released = true;
            lock.notify();
        }
        try {
            decodeThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sets the output mode for frames rendered by the decoder.
     *
     * @param outputMode The output mode.
     */
    public void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        synchronized (lock) {
//            dav1dReleaseFrame(nativeContext, outputBuffer);
            ffmpegReleaseFrame(nativeContext,outputBuffer);
            releaseOutputBufferInternal(outputBuffer);
            maybeNotifyDecodeLoop();
        }
    }

    final boolean isAtLeastOutputStartTimeUs(long timeUs) {
        synchronized (lock) {
            return outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs;
        }
    }

    FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

//    @Nullable
//    FfmpegDecoderException decode(DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
//        if (reset) {
//            nativeContext = ffmpegReset(nativeContext);
//            if (nativeContext == 0) {
//                return new FfmpegDecoderException("Error resetting (see logcat).");
//            }
//        }
//
//        // send packet
//        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
//        int inputSize = inputData.limit();
//        // enqueue origin data
////        int sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputSize, inputBuffer.timeUs);
////        if (sendPacketResult == VIDEO_DECODER_NEED_MORE_FRAME) {
////            outputBuffer.shouldBeSkipped = true;
////            return null;
////        } else if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
////            // need read frame
////            Log.d(TAG, "VIDEO_DECODER_ERROR_READ_FRAME: " + "timeUs=" + inputBuffer.timeUs);
////        } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
////            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
////        }
//
//        // receive frame
//        boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
//        // We need to dequeue the decoded frame from the decoder even when the input data is
//        // decode-only.
////        int getFrameResult = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
//        int getFrameResult = ffmpegDecode(
//                nativeContext,
//                inputBuffer.data,
//                inputSize,
//                inputBuffer.timeUs,
//                outputMode,
//                outputBuffer,
//                decodeOnly
//        );
//        if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
//            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
//        }
//
//        if (getFrameResult == VIDEO_DECODER_NEED_MORE_FRAME) {
//            outputBuffer.shouldBeSkipped = true;
//        }
//
//        if (getFrameResult == 0) {
//            outputBuffer.format = inputBuffer.format;
//        }
//
//        return null;
//    }
    private boolean decode() throws InterruptedException {
        DecoderInputBuffer inputBuffer;
        VideoDecoderOutputBuffer outputBuffer;
        // Wait until we have an input buffer to decode, and an output buffer to decode into.
        synchronized (lock) {
            if (flushed) {
                flushInternal();
            }
            while (!released && !(canDecodeInputBuffer() && canDecodeOutputBuffer()) && !flushed) {
                lock.wait();
            }
            if (released) {
                flushInternal();
                return false;
            }
            if (flushed) {
                // Flushed may have changed after lock.wait() is finished.
                flushInternal();
                // Queued Input Buffers have been cleared, there is no data to decode.
                return true;
            }
            inputBuffer = queuedInputBuffers.removeFirst();
            outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
        }

        if (inputBuffer.isEndOfStream()) {
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
            releaseInputBuffer(inputBuffer);
            synchronized (lock) {
                if (flushed) {
                    outputBuffer.release();
                    flushInternal();
                } else {
                    outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
                    skippedOutputBufferCount = 0;
                    queuedOutputBuffers.addLast(outputBuffer);
                }
            }
        } else {
            outputBuffer.timeUs = inputBuffer.timeUs;
            if (inputBuffer.isFirstSample()) {
                outputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
            }
            if (!isAtLeastOutputStartTimeUs(inputBuffer.timeUs)) {
                outputBuffer.shouldBeSkipped = true;
            }
            @Nullable FfmpegDecoderException exception = null;
            try {

                // send packet
                ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
                int inputOffset = inputData.position();
                int inputSize = inputData.remaining();
                boolean readOnly = false;
                boolean hasInput = false;
                int status;

                while ((status = ffmpegDecode(
                        nativeContext,
                        inputBuffer.data,
                        inputOffset,
                        inputSize,
                        inputBuffer.timeUs,
                        outputMode,
                        outputBuffer,
                        !isAtLeastOutputStartTimeUs(inputBuffer.timeUs),
                        readOnly
                ) )== VIDEO_DECODER_SUCCESS
                        || status ==VIDEO_DECODER_ERROR_READ_FRAME || status >= VIDEO_DECODER_DROP_FRAME || (hasInput && status == VIDEO_DECODER_NEED_MORE_FRAME)) {
                    readOnly = true;
                    if (status == VIDEO_DECODER_ERROR_READ_FRAME){
                        hasInput = true;
                        continue;
                    }
                    if(status >= VIDEO_DECODER_DROP_FRAME){
                        outputBuffer.shouldBeSkipped = true;
                    }
                    synchronized (lock) {
                        if (flushed) {
                            outputBuffer.release();
                            releaseInputBufferInternal(inputBuffer);
                            flushInternal();
                            return true;
                        } if(status == VIDEO_DECODER_NEED_MORE_FRAME){
                            outputBuffer.release();
                        }else if (!isAtLeastOutputStartTimeUs(outputBuffer.timeUs)
                                || outputBuffer.shouldBeSkipped) {
                            if (status >= VIDEO_DECODER_DROP_FRAME){
                                skippedOutputBufferCount+=status;
                            }else{
                                skippedOutputBufferCount++;
                            }
                            outputBuffer.release();
                        } else {
                            outputBuffer.format = FfmpegVideoDecoder.this.format;
                            outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
                            skippedOutputBufferCount = 0;
                            queuedOutputBuffers.addLast(outputBuffer);
                        }
                        if(status >=VIDEO_DECODER_DROP_FRAME || status==VIDEO_DECODER_NEED_MORE_FRAME){
                            if(hasInput){
                                hasInput = false;
                                readOnly = false;
                            }else break;
                        }
                        while (!released && !canDecodeOutputBuffer() && !flushed) {
                            lock.wait();
                        }
                        if (released) {
                            releaseInputBufferInternal(inputBuffer);
                            flushInternal();
                            return false;
                        }
                        if (flushed) {
                            releaseInputBufferInternal(inputBuffer);
                            flushInternal();
                            return true;
                        }
                        outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
                    }
                }
                if (status == VIDEO_DECODER_ERROR_OTHER) {
                    throw new FfmpegDecoderException("ffmpegDecode error: when read frame");
                } else if (status == VIDEO_DECODER_NEED_MORE_FRAME) {
                    outputBuffer.release();
                }else if(status==VIDEO_DECODER_ERROR_INVAILD_DATA){
                    Log.e(TAG,"VIDEO_DECODER_ERROR_INVAILD_DATA");
                    outputBuffer.release();
                    ffmpegReset(nativeContext);
                }else if(status < 0) throw new FfmpegDecoderException("Not Expect error");
            } catch (RuntimeException e) {
                // This can occur if a sample is malformed in a way that the decoder is not robust against.
                // We don't want the process to die in this case, but we do want to propagate the error.
                exception = createUnexpectedDecodeException(e);
            } catch (OutOfMemoryError e) {
                // This can occur if a sample is malformed in a way that causes the decoder to think it
                // needs to allocate a large amount of memory. We don't want the process to die in this
                // case, but we do want to propagate the error.
                exception = createUnexpectedDecodeException(e);
            }catch (FfmpegDecoderException e){
                exception = e;
            }
            if (exception != null) {
                synchronized (lock) {
                    this.exception = exception;
                }
                return false;
            }
            releaseInputBuffer(inputBuffer);
        }

        return true;
    }
    private boolean decodeTest() throws InterruptedException {
        synchronized (lock) {
            if (flushed) {
                flushInternal();
            }
            while (!released && !(stashInput!=null || canDecodeInputBuffer()) && !flushed) {
                lock.wait();
            }
            if (released) {
                flushInternal();
                return false;
            }
            if (flushed) {
                // Flushed may have changed after lock.wait() is finished.
                flushInternal();
                // Queued Input Buffers have been cleared, there is no data to decode.
                return true;
            }
            if (stashInput == null) {
                stashInput = queuedInputBuffers.removeFirst();
            }
            if (stashInput.isEndOfStream()){
                releaseInputBufferInternal(stashInput);
                stashInput = null;
                while (!released && !canDecodeOutputBuffer() && !flushed) {
                    lock.wait();
                }
                if (released) {
                    flushInternal();
                    return false;
                }
                if (flushed) {
                    // Flushed may have changed after lock.wait() is finished.
                    flushInternal();
                    // Queued Input Buffers have been cleared, there is no data to decode.
                    return true;
                }
                VideoDecoderOutputBuffer outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
                outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
                skippedOutputBufferCount = 0;
                queuedOutputBuffers.addLast(outputBuffer);
                return true;
            }
        }
        @Nullable FfmpegDecoderException exception = null;
        try {
            VideoDecoderOutputBuffer outputBuffer = null;
            boolean isStart = stashInput.isFirstSample();
            boolean decodeOnly;
            ByteBuffer inputData = Util.castNonNull(stashInput.data);
            int inputOffset = inputData.position();
            int inputSize = inputData.remaining();
            int status = ffmpegSendPacket(nativeContext, inputData, inputOffset, inputSize, stashInput.timeUs);
            decodeOnly = !isAtLeastOutputStartTimeUs(stashInput.timeUs);
            if (status == VIDEO_DECODER_ERROR_INVAILD_DATA) {
                synchronized (lock) {
                    if (released){
                        flushInternal();
                        return false;
                    }
                    if (flushed){
                        flushInternal();
                        return true;
                    }
                    ffmpegReset(nativeContext);
                    skippedOutputBufferCount++;
                    if (stashInput != null) {
                        releaseInputBufferInternal(stashInput);
                    }
                    stashInput = null;
                    return true;
                }
            }
            if (status == VIDEO_DECODER_ERROR_READ_FRAME){
                Log.e(TAG,"Unexpected sending packet failure");
            }
            if (status==VIDEO_DECODER_ERROR_OTHER)
                throw new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
            if (decodeOnly){
                synchronized (lock){
                    if (released){
                        flushInternal();
                        return false;
                    }
                    if (flushed){
                        flushInternal();
                        return true;
                    }
                }
                int ret = ffmpegReceiveAllFrame(nativeContext, outputBuffer, outputMode,true);
                if (ret!=-1) throw new FfmpegDecoderException("Read Frame Error When dropping frames");
            }else {
                int remainFramesCount;
                do {
                    synchronized (lock) {
                        if (flushed) {
                            flushInternal();
                            return true;
                        }
                        while (!released && !canDecodeOutputBuffer() && !flushed) {
                            lock.wait();
                        }
                        if (released) {
                            flushInternal();
                            return false;
                        }
                        if (flushed) {
                            flushInternal();
                            return true;
                        }
                        outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
                    }
                    remainFramesCount = ffmpegReceiveAllFrame(nativeContext, outputBuffer, outputMode, false);
                    if (remainFramesCount < 0) {
                        throw new FfmpegDecoderException("Read Frame Error");
                    }
                    synchronized (lock) {
                        if(released){
                            outputBuffer.release();
                            flushInternal();
                            return false;
                        }
                        if (flushed) {
                            outputBuffer.release();
                            flushInternal();
                            return true;
                        } else if (!isAtLeastOutputStartTimeUs(outputBuffer.timeUs)) {
                            skippedOutputBufferCount++;
                            outputBuffer.release();
                        } else {
                            outputBuffer.format = this.format;
                            outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
                            skippedOutputBufferCount = 0;
                            if (isStart) {
                                isStart = false;
                                outputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
                            }
                            queuedOutputBuffers.addLast(outputBuffer);
                        }
                    }
                } while (remainFramesCount > 0);
            }
            if (status == VIDEO_DECODER_SUCCESS){
                synchronized (lock){
                    if (released){
                        flushInternal();
                        return false;
                    }
                    if (flushed){
                        flushInternal();
                        return true;
                    }
                    if (stashInput != null) {
                        releaseInputBufferInternal(stashInput);
                    }
                    stashInput = null;
                }
            }
        }catch (RuntimeException e) {
            // This can occur if a sample is malformed in a way that the decoder is not robust against.
            // We don't want the process to die in this case, but we do want to propagate the error.
            exception = createUnexpectedDecodeException(e);
        } catch (OutOfMemoryError e) {
            // This can occur if a sample is malformed in a way that causes the decoder to think it
            // needs to allocate a large amount of memory. We don't want the process to die in this
            // case, but we do want to propagate the error.
            exception = createUnexpectedDecodeException(e);
        }catch (FfmpegDecoderException e){
            exception = e;
        }
        if (exception != null) {
            synchronized (lock) {
                this.exception = exception;
            }
            return false;
        }
        return true;
    }

    private void addSkipBufferCount(int count){
        synchronized (lock){
            skippedOutputBufferCount+=count;
        }
    }

    @GuardedBy("lock")
    private void maybeThrowException() throws FfmpegDecoderException {
        if (this.exception != null) {
            throw this.exception;
        }
    }

    private void releaseInputBuffer(DecoderInputBuffer inputBuffer) {
        synchronized (lock) {
            releaseInputBufferInternal(inputBuffer);
        }
    }

    @GuardedBy("lock")
    private void releaseInputBufferInternal(DecoderInputBuffer inputBuffer) {
        inputBuffer.clear();
        availableInputBuffers[availableInputBufferCount++] = inputBuffer;
    }

    @GuardedBy("lock")
    private void releaseOutputBufferInternal(VideoDecoderOutputBuffer outputBuffer) {
        outputBuffer.clear();
        availableOutputBuffers[availableOutputBufferCount++] = outputBuffer;
    }

    @GuardedBy("lock")
    private boolean canDecodeInputBuffer() {
        return !queuedInputBuffers.isEmpty();
    }

    @GuardedBy("lock")
    private boolean canDecodeOutputBuffer() {
        return availableOutputBufferCount > 0;
    }

    @GuardedBy("lock")
    private void maybeNotifyDecodeLoop() {
        if (canDecodeInputBuffer() || canDecodeOutputBuffer()) {
            lock.notify();
        }
    }

    @GuardedBy("lock")
    private void flushInternal() {
        skippedOutputBufferCount = 0;
        if (stashInput !=null){
            releaseInputBuffer(stashInput);
            stashInput = null;
        }
        if (dequeuedInputBuffer != null) {
            releaseInputBufferInternal(dequeuedInputBuffer);
            dequeuedInputBuffer = null;
        }
        while (!queuedInputBuffers.isEmpty()) {
            releaseInputBufferInternal(queuedInputBuffers.removeFirst());
        }
        while (!queuedOutputBuffers.isEmpty()) {
            queuedOutputBuffers.removeFirst().release();
        }
        ffmpegReset(nativeContext);
        flushed = false;
    }

    private void run() {
        try {
            while (decodeTest()) {
                // Do nothing.
            }
        } catch (InterruptedException e) {
            // Not expected.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Renders output buffer to the given surface. Must only be called when in {@link
     * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
     *
     * @param outputBuffer Output buffer.
     * @param surface      Output surface.
     * @throws FfmpegDecoderException Thrown if called with invalid output mode or frame
     *                                rendering fails.
     */
    public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws FfmpegDecoderException {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw new FfmpegDecoderException("Invalid output mode.");
        }
        if (ffmpegRenderFrame(
                nativeContext, surface,
                outputBuffer) == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("Buffer render error: ");
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads, int degree);

    private native long ffmpegReset(long context);

    private native void ffmpegRelease(long context);

    private native int ffmpegRenderFrame(
            long context, Surface surface, VideoDecoderOutputBuffer outputBuffer);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
     * error occurred.
     */
    private native int ffmpegSendPacket(long context, ByteBuffer encodedData,int offset, int length,
                                        long inputTime);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_NEED_MORE_FRAME}
     * if successful but the frame is decode-only, {@link #VIDEO_DECODER_ERROR_OTHER} if an error
     * occurred.
     */
    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);
    private native void ffmpegReleaseFrame(long context,VideoDecoderOutputBuffer outputBuffer);
    private native int ffmpegDecode(long context,ByteBuffer encodedData,int offset,int length,long inputTime,int outputMode, VideoDecoderOutputBuffer outputBuffer ,boolean decodeOnly,boolean readOnly);
    private native int ffmpegReceiveAllFrame(long context,@Nullable VideoDecoderOutputBuffer outputBuffer,int outputMode,boolean decodeOnly);

}