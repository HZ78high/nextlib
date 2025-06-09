
#include <android/log.h>
#include <jni.h>
#include <cstdlib>
#include <android/native_window_jni.h>
#include <algorithm>
#include "ffcommon.h"
#include <mutex>
#include <deque>
extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <cstdint>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define ALIGN(x, a) (((x) + ((a) - 1)) & ~((a) - 1))

static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_NEED_MORE_FRAME = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
static const int VIDEO_DECODER_ERROR_READ_FRAME = -3;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -4;
static const int VIDEO_DECODER_DROP_FRAME = 1;

namespace {
// YUV plane indices.
    const int kPlaneY = 0;
    const int kPlaneU = 1;
    const int kPlaneV = 2;
    const int kMaxPlanes = 3;

// Android YUV format. See:
// https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12.
    const int kImageFormatYV12 = 0x32315659;
    constexpr int AlignTo16(int value) { return (value + 15) & (~15); }
}
struct JniContext {
    ~JniContext() {
        LOGI("~JniContext()");
        clear_frames();
        if (native_window) {
            LOGI("Release native_window");
            ANativeWindow_release(native_window);
        }
    }

    bool MaybeAcquireNativeWindow(JNIEnv *env, jobject new_surface) {
        if (new_surface == nullptr) {
            if (native_window) {
                ANativeWindow_release(native_window);
                native_window = nullptr;
            }
            if (surface) {
                env->DeleteGlobalRef(surface);
                surface = nullptr;
            }
            LOGI("New Surface is nullptr");
            return false;
        }
        if (surface && env->IsSameObject(surface, new_surface)) {
            return true; // 无需更换
        }
        if (native_window) {
            ANativeWindow_release(native_window);
            native_window = nullptr;
        }
        if (surface) {
            env->DeleteGlobalRef(surface);
            surface = nullptr;
        }
        LOGI("New Surface");
        native_window_width = 0;
        native_window_height = 0;
        native_window = ANativeWindow_fromSurface(env, new_surface);
        if (native_window == nullptr) {
            LOGE("kJniStatusANativeWindowError");
            surface = nullptr;
            return false;
        }
        surface = env->NewGlobalRef(new_surface);;
        if (surface == nullptr) {
            ANativeWindow_release(native_window);
            native_window = nullptr;
            LOGE("Failed to create global ref for surface");
            return false;
        }
        return true;
    }

    jfieldID data_field{};
    jfieldID decoder_private_field{};
    jfieldID display_width_field{};
    jfieldID display_height_field{};
    jfieldID yuvPlanes_field{};
    jfieldID yuvStrides_field{};
    jfieldID skipped_output_buffer_count_field{};
    jmethodID init_for_yuv_frame_method{};
    jmethodID init_method{};
    jmethodID init_for_private_frame_method;
    jmethodID isAtLeastOutputStartTimeUs_method{};
    jmethodID add_skip_buffer_count_method{};

    AVCodecContext *codecContext{};
    SwsContext *swsContext{};

    ANativeWindow *native_window = nullptr;
    jobject surface = nullptr;
    int rotate_degree = 0;
    int native_window_width = 0;
    int native_window_height = 0;
    void push_frame(AVFrame* frame) {
        std::lock_guard<std::mutex> lock(mutex_);
        stashed_frames.push_back(frame);
    }

    auto remain_frame_count() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stashed_frames.size();
    }

    AVFrame* pop_frame() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stashed_frames.empty()) return nullptr;
        AVFrame* frame = stashed_frames.front();
        stashed_frames.pop_front();
        return frame;
    }

    size_t clear_frames() {
        std::lock_guard<std::mutex> lock(mutex_);
        auto size = stashed_frames.size();
        for (AVFrame* frame : stashed_frames) {
            av_frame_free(&frame);
        }
        stashed_frames.clear();
        return size;
    }
private:
    std::deque<AVFrame*> stashed_frames;
    std::mutex mutex_;
};

JniContext *createVideoContext(JNIEnv *env,
                               AVCodec *codec,
                               jbyteArray extraData,
                               jint threads,
                               jint degree) {
    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) {
        LOGE("Failed to allocate context.");
        return nullptr;
    }

    if (extraData) {
        jsize size = env->GetArrayLength(extraData);
        codecContext->extradata_size = size;
        codecContext->extradata = (uint8_t *) av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!codecContext->extradata) {
            LOGE("Failed to allocate extradata.");
            releaseContext(&codecContext);
            return nullptr;
        }
        env->GetByteArrayRegion(extraData, 0, size, (jbyte *) codecContext->extradata);
    }

    // opt decode speed.
//    codecContext->skip_loop_filter = AVDISCARD_ALL;
//    codecContext->skip_frame = AVDISCARD_DEFAULT;
    codecContext->thread_count = threads;
    codecContext->thread_type = FF_THREAD_FRAME;
    codecContext->err_recognition = AV_EF_IGNORE_ERR;
    int result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) {
        logError("avcodec_open2", result);
        releaseContext(&codecContext);
        return nullptr;
    }

    auto *jniContext = new JniContext();
    if (!jniContext) {
        LOGE("Failed to allocate JniContext.");
        releaseContext(&codecContext);
        return nullptr;
    }

    // rotate
    jniContext->rotate_degree = degree;

    jniContext->codecContext = codecContext;

    // Populate JNI References.
    jclass outputBufferClass = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
    jclass FfmpegVideoDecoderClass = env->FindClass("io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegVideoDecoder");
    if (!outputBufferClass) {
        LOGE("Failed to find VideoDecoderOutputBuffer class.");
        releaseContext(&codecContext);
        delete jniContext;
        return nullptr;
    }
    if(!FfmpegVideoDecoderClass){
        LOGE("Failed to find SimpleDecoder class.");
        releaseContext(&codecContext);
        delete jniContext;
        return nullptr;
    }

    jniContext->data_field = env->GetFieldID(outputBufferClass, "data", "Ljava/nio/ByteBuffer;");
    jniContext->yuvStrides_field = env->GetFieldID(outputBufferClass, "yuvStrides", "[I");
    jniContext->yuvPlanes_field = env->GetFieldID(outputBufferClass, "yuvPlanes", "[Ljava/nio/ByteBuffer;");
    jniContext->init_for_yuv_frame_method = env->GetMethodID(outputBufferClass, "initForYuvFrame", "(IIIII)Z");
    jniContext->init_method = env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");
    jniContext->decoder_private_field = env->GetFieldID(outputBufferClass, "decoderPrivate", "J");
    jniContext->init_for_private_frame_method = env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
    jniContext->display_width_field = env->GetFieldID(outputBufferClass, "width", "I");
    jniContext->display_height_field = env->GetFieldID(outputBufferClass, "height", "I");
    jniContext->skipped_output_buffer_count_field = env->GetFieldID(outputBufferClass,"skippedOutputBufferCount","I");
    jniContext->isAtLeastOutputStartTimeUs_method = env->GetMethodID(FfmpegVideoDecoderClass,"isAtLeastOutputStartTimeUs","(J)Z");
    jniContext->add_skip_buffer_count_method = env->GetMethodID(FfmpegVideoDecoderClass,"addSkipBufferCount","(I)V");
    // 检查所有JNI引用是否成功获取
    if (!jniContext->data_field || !jniContext->yuvStrides_field || !jniContext->yuvPlanes_field ||
        !jniContext ->display_height_field || !jniContext->display_width_field ||
        !jniContext->add_skip_buffer_count_method||!jniContext->skipped_output_buffer_count_field||
        !jniContext ->decoder_private_field || !jniContext->init_for_private_frame_method||
        !jniContext->init_for_yuv_frame_method || !jniContext->init_method || !jniContext->isAtLeastOutputStartTimeUs_method) {
        LOGE("Failed to get field or method IDs.");
        releaseContext(&codecContext);
        delete jniContext;
        return nullptr;
    }

    return jniContext;
}


extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegInitialize(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jstring codec_name,
                                                                                 jbyteArray extra_data,
                                                                                 jint threads,
                                                                                 jint degree) {
    AVCodec *codec = getCodecByName(env, codec_name);
    if (!codec) {
        LOGE("Codec not found.");
        return 0L;
    }

    return (jlong) createVideoContext(env, codec, extra_data, threads ,degree);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReset(JNIEnv *env, jobject thiz,
                                                                            jlong jContext) {
    if (jContext == 0){
        return 0;
    }
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    jniContext->clear_frames();
    AVCodecContext *context = jniContext->codecContext;
    if (!context) {
        LOGE("Tried to reset without a context.");
        return 0L;
    }

    avcodec_flush_buffers(context);
    return (jlong) jniContext;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegRelease(JNIEnv *env, jobject thiz,
                                                                              jlong jContext) {
    if(jContext == 0){
        return;
    }
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *context = jniContext->codecContext;
    SwsContext *swsContext = jniContext->swsContext;
    auto surface = jniContext->surface;
    if (swsContext) {
        sws_freeContext(swsContext);
        jniContext->swsContext = nullptr;
    }
    if (context) {
        releaseContext(&context);
    }
    if (surface!= nullptr){
        env->DeleteGlobalRef(surface);
        jniContext->surface = nullptr;
    }
    delete jniContext;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegRenderFrame(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jlong jContext,
                                                                                  jobject surface,
                                                                                  jobject output_buffer) {
    if (jContext == 0){
        return VIDEO_DECODER_ERROR_OTHER;
    }
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVFrame* frame = reinterpret_cast<AVFrame*>(
            env->GetLongField(output_buffer, jniContext->decoder_private_field));
    if (frame == nullptr) {
        LOGE("Failed to get frame.");
        return VIDEO_DECODER_SUCCESS;
    }

    auto displayed_width = env->GetIntField(output_buffer, jniContext->display_width_field);
    auto displayed_height = env->GetIntField(output_buffer, jniContext->display_height_field);
    retry_acquire:
    if (!jniContext->MaybeAcquireNativeWindow(env, surface)) {
        return VIDEO_DECODER_ERROR_OTHER;
    }
    if (jniContext->native_window_width != displayed_width ||
        jniContext->native_window_height != displayed_height) {
        LOGI("ANativeWindow_setBuffersGeometry width: %d height %d\nCurrent window: width: %d height: %d"
             ,displayed_width,displayed_height,jniContext->native_window_width,jniContext->native_window_height);
        if (ANativeWindow_setBuffersGeometry(
                jniContext->native_window,
                displayed_width,
                displayed_height,
                kImageFormatYV12)) {
            LOGE("kJniStatusANativeWindowError");
            return VIDEO_DECODER_ERROR_OTHER;
        }

        jniContext->native_window_width = displayed_width;
        jniContext->native_window_height = displayed_height;

        // Initializing swsContext with AV_PIX_FMT_YUV420P, which is equivalent to YV12.
        // The only difference is the order of the u and v planes.
        SwsContext *swsContext = sws_getCachedContext(jniContext->swsContext,
                                                      displayed_width, displayed_height,
                                                jniContext->codecContext->pix_fmt,
                                                displayed_width, displayed_height,
                                                AV_PIX_FMT_YUV420P,
                                                SWS_BILINEAR, nullptr, nullptr, nullptr);

        if (!swsContext) {
            LOGE("Failed to allocate swsContext.");
            return VIDEO_DECODER_ERROR_OTHER;
        }
        jniContext->swsContext = swsContext;
    }

    ANativeWindow_Buffer native_window_buffer;
    int result = ANativeWindow_lock(jniContext->native_window, &native_window_buffer, nullptr);
    if (result == -19) {
        if (jniContext->native_window != nullptr) {
            ANativeWindow_release(jniContext->native_window);
        }
        jniContext->native_window = nullptr;
        if (jniContext->surface != nullptr) {
            env->DeleteGlobalRef(jniContext->surface);
        }
        jniContext->surface = nullptr;
        goto retry_acquire;
    } else if (result || native_window_buffer.bits == nullptr) {
        LOGE("kJniStatusANativeWindowError");
        return VIDEO_DECODER_ERROR_OTHER;
    }
    // 直接用AVFrame的数据和linesize
    auto *planeY = frame->data[0];
    auto *planeU = frame->data[1];
    auto *planeV = frame->data[2];

    int strideY = frame->linesize[0];
    int strideU = frame->linesize[1];
    int strideV = frame->linesize[2];


    // source planes from VideoDecoderOutputBuffer
//    jobject yuvPlanes_object = env->GetObjectField(output_buffer, jniContext->yuvPlanes_field);
//    auto yuvPlanes_array = jobjectArray(yuvPlanes_object);
//    jobject yuvPlanesY = env->GetObjectArrayElement(yuvPlanes_array, kPlaneY);
//    jobject yuvPlanesU = env->GetObjectArrayElement(yuvPlanes_array, kPlaneU);
//    jobject yuvPlanesV = env->GetObjectArrayElement(yuvPlanes_array, kPlaneV);

//    auto *planeY = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(yuvPlanesY));
//    auto *planeU = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(yuvPlanesU));
//    auto *planeV = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(yuvPlanesV));

    // source strides from VideoDecoderOutputBuffer
//    jobject yuvStrides_object = env->GetObjectField(output_buffer, jniContext->yuvStrides_field);
//    auto *yuvStrides_array = reinterpret_cast<jintArray *>(&yuvStrides_object);
//    int *yuvStrides = env->GetIntArrayElements(*yuvStrides_array, nullptr);

//    int strideY = yuvStrides[kPlaneY];
//    int strideU = yuvStrides[kPlaneU];
//    int strideV = yuvStrides[kPlaneV];


    const int32_t native_window_buffer_uv_height = (native_window_buffer.height + 1) / 2;
    auto native_window_buffer_bits = reinterpret_cast<uint8_t *>(native_window_buffer.bits);
    const int native_window_buffer_uv_stride = AlignTo16(native_window_buffer.stride / 2);
    const int v_plane_height = std::min(native_window_buffer_uv_height, displayed_height);

    const int y_plane_size = native_window_buffer.stride * native_window_buffer.height;
    const int v_plane_size = v_plane_height * native_window_buffer_uv_stride;

    // source data
    uint8_t *src[3] = {planeY, planeU, planeV};

    // source strides
    int src_stride[3] = {strideY, strideU, strideV};

    // destination data with u and v swapped
    uint8_t *dest[3] = {native_window_buffer_bits,
                        native_window_buffer_bits + y_plane_size + v_plane_size,
                        native_window_buffer_bits + y_plane_size};

    // destination strides
    int dest_stride[3] = {native_window_buffer.stride,
                          native_window_buffer_uv_stride,
                          native_window_buffer_uv_stride};


    //Perform color space conversion using sws_scale.
    //Convert the source data (src) with specified strides (src_stride) and displayed height,
    //and store the result in the destination data (dest) with corresponding strides (dest_stride).
    sws_scale(jniContext->swsContext,
              src, src_stride,
              0, displayed_height,
              dest, dest_stride);

//    env->ReleaseIntArrayElements(*yuvStrides_array, yuvStrides, 0);

    if (ANativeWindow_unlockAndPost(jniContext->native_window)) {
        LOGE("kJniStatusANativeWindowError");
        return VIDEO_DECODER_ERROR_OTHER;
    }

    return VIDEO_DECODER_SUCCESS;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegSendPacket(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jlong jContext,
                                                                                 jobject encoded_data,
                                                                                 jint offset,
                                                                                 jint length,
                                                                                 jlong input_time) {
    if (jContext == 0){
        return VIDEO_DECODER_ERROR_OTHER;
    }
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;

    auto *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(encoded_data);
    AVPacket *packet = av_packet_alloc();
    packet->data = inputBuffer+offset;
    packet->size = length;
    packet->pts = input_time;

    // Queue input data.
    int result = avcodec_send_packet(avContext, packet);
    av_packet_free(&packet);
    if (result == AVERROR(EAGAIN)){
        return VIDEO_DECODER_ERROR_READ_FRAME;
    }
    if (result) {
        logError("avcodec_send_packet-video", result);
        if (result == AVERROR_INVALIDDATA) {
            // need more data
            return VIDEO_DECODER_ERROR_INVALID_DATA;
        } else {
            return VIDEO_DECODER_ERROR_OTHER;
        }
    }
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReceiveFrame(JNIEnv *env,
                                                                                   jobject thiz,
                                                                                   jlong jContext,
                                                                                   jint output_mode,
                                                                                   jobject output_buffer,
                                                                                   jboolean decode_only) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;

    AVFrame *frame = av_frame_alloc();
    if (!frame) {
        LOGE("Failed to allocate output frame.");
        return VIDEO_DECODER_ERROR_OTHER;
    }
    int result = avcodec_receive_frame(avContext, frame);

    // fail
    if (result == AVERROR_EOF || result == AVERROR(EAGAIN)) {
        // This is not an error. The input data was decode-only or no displayable
        // frames are available.
        av_frame_free(&frame);
        return VIDEO_DECODER_NEED_MORE_FRAME;
    }
    if (result) {
        av_frame_free(&frame);
        logError("avcodec_receive_frame", result);
        return VIDEO_DECODER_ERROR_OTHER;
    }
    auto shouldKeep = env->CallBooleanMethod(thiz, jniContext->isAtLeastOutputStartTimeUs_method,frame->pts);
    if(!shouldKeep || decode_only){
        av_frame_free(&frame);
        return VIDEO_DECODER_DROP_FRAME;
    }
    // success
    // init time and mode
    env->CallVoidMethod(output_buffer, jniContext->init_method, frame->pts, output_mode, nullptr);

    // init data
    const jboolean init_result = env->CallBooleanMethod(
            output_buffer, jniContext->init_for_yuv_frame_method,
            frame->width,
            frame->height,
            frame->linesize[0], frame->linesize[1],
            0);
    if (env->ExceptionCheck()) {
        // Exception is thrown in Java when returning from the native call.
        return VIDEO_DECODER_ERROR_OTHER;
    }
    if (!init_result) {
        return VIDEO_DECODER_ERROR_OTHER;
    }

    jobject data_object = env->GetObjectField(output_buffer, jniContext->data_field);
    auto *data = reinterpret_cast<jbyte *>(env->GetDirectBufferAddress(data_object));
    const int32_t uvHeight = (frame->height + 1) / 2;
    const uint64_t yLength = frame->linesize[0] * frame->height;
    const uint64_t uvLength = frame->linesize[1] * uvHeight;

    // TODO: Support rotate YUV data

    memcpy(data, frame->data[0], yLength);
    memcpy(data + yLength, frame->data[1], uvLength);
    memcpy(data + yLength + uvLength, frame->data[2], uvLength);

    av_frame_free(&frame);

    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegDecode(
        JNIEnv *env,
        jobject thiz,
        jlong jContext,
        jobject encoded_data,
        jint offset,
        jint length,
        jlong input_time,
        jint output_mode,
        jobject output_buffer,
        jboolean decodeOnly,
        jboolean readOnly) {
    LOGI("Calling Native decodeOnly %d",decodeOnly);
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;
    // 1. Prepare packet if input exists
    AVPacket *packet = nullptr;
    if(!readOnly) {
        if (encoded_data != nullptr && length > 0) {
            auto *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(encoded_data);
            if (!inputBuffer) {
                logError("GetDirectBufferAddress failed", -1);
                return VIDEO_DECODER_NEED_MORE_FRAME;
            }

            packet = av_packet_alloc();
            if (!packet) {
                logError("Failed to allocate AVPacket", -1);
                return VIDEO_DECODER_ERROR_OTHER;
            }
            packet->data = inputBuffer + offset;
            packet->size = length;
            packet->pts = input_time;
        } else {
            logError("Input data is null or zero", -1);
            return VIDEO_DECODER_NEED_MORE_FRAME;
        }
    }
    // Helper lambda: try receive frames until no more
    auto maybe_receive_all_frames = [&](bool decode_Only) -> int {
        int ret;
        AVFrame *frame = av_frame_alloc();
        if (!frame) {
            logError("Failed to allocate AVFrame", -1);
            return VIDEO_DECODER_ERROR_OTHER;
        }
        int dropFrameCount = 0;
        while (true) {
            ret = avcodec_receive_frame(avContext, frame);
            auto frameTime = frame->pts;
            if(frameTime<0) frameTime = input_time;
            auto shouldKeep = env->CallBooleanMethod(thiz,
                                                     jniContext->isAtLeastOutputStartTimeUs_method,
                                                     frameTime);
            LOGI("Input time %lld Frame Time %lld shouldKeep:%d dropFrameCount: %d", input_time, frame->pts,
                 shouldKeep,dropFrameCount);
            if (ret == AVERROR(EAGAIN)) {
                av_frame_free(&frame);
                LOGI("Drop Frame AVERROR(EAGAIN) Count: %d",dropFrameCount);
                if (!dropFrameCount) return VIDEO_DECODER_NEED_MORE_FRAME;
                return dropFrameCount;
            }
            if (ret) {
                av_frame_free(&frame);
                LOGE("Error in Read Frame");
                return ret;
            }
            if (!shouldKeep || decode_Only) {
                LOGI("Drop Frame Time");
                av_frame_unref(frame);
                dropFrameCount++;
                continue;
            }
            // 填充Java output_buffer数据
            env->CallVoidMethod(output_buffer, jniContext->init_method, frameTime, output_mode,
                                nullptr);
            jboolean init_result = env->CallBooleanMethod(
                    output_buffer, jniContext->init_for_yuv_frame_method,
                    frame->width, frame->height,
                    frame->linesize[0], frame->linesize[1],
                    0);
            if (env->ExceptionCheck() || !init_result) {
                av_frame_free(&frame);
                return VIDEO_DECODER_ERROR_OTHER;
            }

            jobject data_object = env->GetObjectField(output_buffer, jniContext->data_field);
            auto *data = (jbyte *) env->GetDirectBufferAddress(data_object);

            int uvHeight = (frame->height + 1) / 2;
            size_t yLength = frame->linesize[0] * frame->height;
            size_t uvLength = frame->linesize[1] * uvHeight;

            memcpy(data, frame->data[0], yLength);
            memcpy(data + yLength, frame->data[1], uvLength);
            memcpy(data + yLength + uvLength, frame->data[2], uvLength);

            av_frame_free(&frame);
            return 0;
        }
        
    };
    auto maybe_receive_all_frames_test = [&](bool decode_Only) -> int {
        int ret;
        AVFrame *frame = av_frame_alloc();
        if (!frame) {
            logError("Failed to allocate AVFrame", -1);
            return VIDEO_DECODER_ERROR_OTHER;
        }
        int dropFrameCount = 0;
        do{
            ret = avcodec_receive_frame(avContext, frame);
            auto frameTime = frame->pts;
            if(frameTime<0) frameTime = input_time;
            auto shouldKeep = env->CallBooleanMethod(thiz,
                                                     jniContext->isAtLeastOutputStartTimeUs_method,
                                                     frameTime);
            LOGI("Input time %lld Frame Time %lld shouldKeep:%d dropFrameCount: %d", input_time, frame->pts,
                 shouldKeep,dropFrameCount);
            if (ret == AVERROR(EAGAIN)) {
                av_frame_free(&frame);
                LOGI("Drop Frame AVERROR(EAGAIN) Count: %d",dropFrameCount);
                if (!dropFrameCount) return VIDEO_DECODER_NEED_MORE_FRAME;
                return dropFrameCount;
            }
            if (ret) {
                av_frame_free(&frame);
                LOGE("Error in Read Frame");
                return ret;
            }
            if (!shouldKeep || decode_Only) {
                LOGI("Drop Frame Time");
                av_frame_unref(frame);
                dropFrameCount++;
                continue;
            }
            env->CallVoidMethod(output_buffer, jniContext->init_method, frameTime, output_mode,
                                nullptr);
            env->SetLongField(output_buffer, jniContext->decoder_private_field,
                              (uint64_t)frame);
            env->CallVoidMethod(output_buffer, jniContext->init_for_private_frame_method,
                                frame->width, frame->height);
            return 0;
        } while (true);
    };
    if (readOnly) return maybe_receive_all_frames_test(decodeOnly);
    // 2. 发送包，如果失败是EAGAIN，先拉帧释放缓冲再重试

    int result;
    // 缓冲区满，先拉帧释放
    result = avcodec_send_packet(avContext, packet);
    if(result) {
        av_packet_free(&packet);
        if(result == AVERROR(EAGAIN)){
            logError("avcodec_send_packet -error -need reed frame", result);
            return VIDEO_DECODER_ERROR_READ_FRAME;
        }else if(result == AVERROR_INVALIDDATA){
            logError("avcodec_send_packet -error -Invalid data", result);
            return VIDEO_DECODER_ERROR_INVALID_DATA;
        }
        else{
            logError("avcodec_send_packet -error", result);
            return VIDEO_DECODER_ERROR_OTHER;
        }
    }
    int rec_ret;
    rec_ret = maybe_receive_all_frames_test(decodeOnly);
    av_packet_free(&packet);
    if (rec_ret > 0) {
        LOGI("Drop Frame");
        return rec_ret;
    }
    if (rec_ret == -1) {
        LOGI("need more frame");
        return -1;
    }
    if (rec_ret) {
        logError("avcodec_send_packet-video-readframe", rec_ret);
        return VIDEO_DECODER_ERROR_OTHER;
    }
    return 0;
}
extern "C"
JNIEXPORT void JNICALL
        Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReleaseFrame(JNIEnv *env,
                                                                                                      jobject thiz, jlong jContext, jobject jOutputBuffer){
    if(!jContext){
        return;
    }
    JniContext* const context = reinterpret_cast<JniContext*>(jContext);
    AVFrame *frame = (AVFrame*)env->GetLongField(
            jOutputBuffer, context->decoder_private_field);
    env->SetLongField(jOutputBuffer, context->decoder_private_field, 0);
    if (frame != nullptr) {
        av_frame_free(&frame);
    }
}
extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReceiveAllFrame(JNIEnv *env,
                                                                                                 jobject thiz,jlong jContext,
                                                                                                 jobject output_buffer,
                                                                                                 jint output_mode,
                                                                                                 jboolean decodeOnly){
    if(!jContext){
        return -1;
    }
    JniContext* const jniContext = reinterpret_cast<JniContext*>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;
    AVFrame *frame = nullptr;
    size_t drop_frame_count = 0;
    if (!decodeOnly) {
        frame = jniContext->pop_frame();
        if (frame) {
            env->CallVoidMethod(output_buffer, jniContext->init_method, frame->pts, output_mode,
                                nullptr);
            env->SetLongField(output_buffer, jniContext->decoder_private_field,
                              (uint64_t) frame);
            env->CallVoidMethod(output_buffer, jniContext->init_for_private_frame_method,
                                frame->width, frame->height);
            return jniContext->remain_frame_count();
        }
    } else{
        drop_frame_count +=jniContext->clear_frames();
    }

    int ret;
    int read_count = 0;
    frame = av_frame_alloc();
    do {
        ret = avcodec_receive_frame(avContext, frame);
        if (ret == AVERROR(EAGAIN)) {
            av_frame_free(&frame);
            LOGI("read_count: %d\ndrop_frame_count: %d decodeOnly: %d",read_count,drop_frame_count,decodeOnly);
            if (decodeOnly) {
                if (drop_frame_count > 0) {
                    env->CallVoidMethod(thiz, jniContext->add_skip_buffer_count_method,
                                        drop_frame_count);
                }
                return -1;
            }
            return jniContext->remain_frame_count();
        }
        if (ret){
            av_frame_free(&frame);
            return -2;
        }

        LOGI("time: %lld",frame->pts);
        if (decodeOnly){
            drop_frame_count++;
            av_frame_unref(frame);
            continue;
        }
        if (!read_count){
            env->CallVoidMethod(output_buffer, jniContext->init_method, frame->pts, output_mode,
                                nullptr);
            env->SetLongField(output_buffer, jniContext->decoder_private_field,
                              (uint64_t)frame);
            env->CallVoidMethod(output_buffer, jniContext->init_for_private_frame_method,
                                frame->width, frame->height);
        } else {
            jniContext->push_frame(frame);
        }
        read_count++;
        frame = av_frame_alloc();
    } while (true);
}