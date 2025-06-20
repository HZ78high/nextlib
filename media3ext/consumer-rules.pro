# This prevents the names of native methods from being obfuscated.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
    *;
}
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoDecoder {
    boolean isAtLeastOutputStartTimeUs(long);
    void addSkipBufferCount(int);
}