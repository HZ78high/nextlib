#!/bin/bash

# Versions
VPX_VERSION=1.15.1
AOM_VERSION=3.12.1
MBEDTLS_VERSION=3.6.3
FFMPEG_VERSION=7.1.1
export ANDROID_NDK_HOME=/home/hyz/Android-dv/nkd/android-ndk-r28
CMAKE_HOME=/mnt/c/_Linux/cmake-4.0.0-linux-x86_64
# Directories
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR=$BASE_DIR/build
OUTPUT_DIR=$BASE_DIR/output
SOURCES_DIR=$BASE_DIR/sources
FFMPEG_DIR=$SOURCES_DIR/ffmpeg-$FFMPEG_VERSION
# FFMPEG_DIR_OLD=$SOURCES_DIR/ffmpeg-6.0
VPX_DIR=$SOURCES_DIR/libvpx-$VPX_VERSION
AOM_DIR=$SOURCES_DIR/libaom-$AOM_VERSION
MBEDTLS_DIR=$SOURCES_DIR/mbedtls-$MBEDTLS_VERSION
VPX_OUT_DIR=$BUILD_DIR/vpx-$VPX_VERSION
AOM_OUT_DIR=$BUILD_DIR/aom-$AOM_VERSION
MEDTLS_OUT_DIR=$BUILD_DIR/mbedtls-$MBEDTLS_VERSION

# Configuration
ANDROID_ABIS="x86 x86_64 armeabi-v7a arm64-v8a"
ANDROID_PLATFORM=21
ENABLED_DECODERS="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo"
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || sysctl -n hw.physicalcpu || echo 4)

# Set up host platform variables
HOST_PLATFORM="linux-x86_64"
case "$OSTYPE" in
darwin*) HOST_PLATFORM="darwin-x86_64" ;;
linux*) HOST_PLATFORM="linux-x86_64" ;;
msys)
  case "$(uname -m)" in
  x86_64) HOST_PLATFORM="windows-x86_64" ;;
  i686) HOST_PLATFORM="windows" ;;
  esac
  ;;
esac

# Build tools
TOOLCHAIN_PREFIX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_PLATFORM}"
CMAKE_EXECUTABLE=$CMAKE_HOME/bin/cmake

mkdir -p $SOURCES_DIR

function downloadLibVpx() {
  pushd $SOURCES_DIR
  echo "Downloading Vpx source code of version $VPX_VERSION..."
  VPX_FILE=libvpx-$VPX_VERSION.tar.gz
  curl -L "https://github.com/webmproject/libvpx/archive/refs/tags/v${VPX_VERSION}.tar.gz" -o $VPX_FILE
  [ -e $VPX_FILE ] || { echo "$VPX_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $VPX_FILE
  rm $VPX_FILE
  popd
}

function downloadLibAom() {
  pushd $SOURCES_DIR
  echo "Downloading Aom source code of version $AOM_VERSION..."
  AOM_FILE=libaom-$AOM_VERSION.tar.gz
  curl -L "https://storage.googleapis.com/aom-releases/libaom-${AOM_VERSION}.tar.gz" -o $AOM_FILE
  [ -e $AOM_FILE ] || { echo "$AOM_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $AOM_FILE
  rm $AOM_FILE
  popd
}

function downloadMbedTLS() {
  pushd $SOURCES_DIR
  echo "Downloading mbedtls source code of version $MBEDTLS_VERSION..."
  MBEDTLS_FILE=mbedtls-$MBEDTLS_VERSION.tar.gz
  curl -L "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v${MBEDTLS_VERSION}.tar.gz" -o $MBEDTLS_FILE
  [ -e $MBEDTLS_FILE ] || { echo "$MBEDTLS_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $MBEDTLS_FILE
  rm $MBEDTLS_FILE
  popd
}

function downloadFfmpeg() {
  pushd $SOURCES_DIR
  echo "Downloading FFmpeg source code of version $FFMPEG_VERSION..."
  FFMPEG_FILE=ffmpeg-$FFMPEG_VERSION.tar.gz
  curl -L "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" -o $FFMPEG_FILE
  [ -e $FFMPEG_FILE ] || { echo "$FFMPEG_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $FFMPEG_FILE
  rm $FFMPEG_FILE
  popd
}

function buildLibVpx() {
  local ABI
  pushd $VPX_DIR

  for ABI in $ANDROID_ABIS; do
  {
    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      EXTRA_BUILD_FLAGS="--force-target=armv7-android-gcc --enable-neon --enable-neon-asm"
      TOOLCHAIN=armv7a-linux-androideabi21-      
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang
      ;;
    arm64-v8a)
      EXTRA_BUILD_FLAGS="--force-target=armv8-android-gcc --enable-neon"
      TOOLCHAIN=aarch64-linux-android21-      
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang
      ;;
    x86)
      EXTRA_BUILD_FLAGS="--force-target=x86-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=i686-linux-android21-
      ;;
    x86_64)
      EXTRA_BUILD_FLAGS="--force-target=x86_64-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic --disable-neon --disable-neon-asm"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=x86_64-linux-android21-
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac
    local BUILD_DIR=${VPX_DIR}/build_vpx_${ABI}
    rm -rf $BUILD_DIR
    mkdir -p $BUILD_DIR
    cd $BUILD_DIR
    CC=${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang \
      CXX=${CC}++ \
      LD=${CC} \
      AR=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
      AS=${VPX_AS} \
      STRIP=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
      NM=${TOOLCHAIN_PREFIX}/bin/llvm-nm \
      ../configure \
      --prefix=$VPX_OUT_DIR/$ABI \
      --libc="${TOOLCHAIN_PREFIX}/sysroot" \
      --enable-vp8 \
      --enable-vp9 \
      --enable-static \
      --disable-shared \
      --disable-examples \
      --disable-docs \
      --enable-realtime-only \
      --enable-install-libs \
      --enable-multithread \
      --disable-webm-io \
      --disable-libyuv \
      --enable-better-hw-compatibility \
      --disable-runtime-cpu-detect \
      ${EXTRA_BUILD_FLAGS}

    make -j$JOBS
    make install
  } &
  done
  wait
  popd
}

function buildMbedTLS() {
    local ABI
    pushd $MBEDTLS_DIR
    for ABI in $ANDROID_ABIS; do
    {
      local CMAKE_BUILD_DIR=$MBEDTLS_DIR/mbedtls_build_${ABI}
      rm -rf ${CMAKE_BUILD_DIR}
      mkdir -p ${CMAKE_BUILD_DIR}
      cd ${CMAKE_BUILD_DIR}

      ${CMAKE_EXECUTABLE} .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
        -DANDROID_ABI=$ABI \
        -DCMAKE_TOOLCHAIN_FILE=${BASE_DIR}/Cmake/mbedtls/android.cmake \
        -DCMAKE_INSTALL_PREFIX=$MEDTLS_OUT_DIR/$ABI \
        -DENABLE_TESTING=0

      make -j$JOBS
      make install
    } &
    done
    wait
    popd
}


# shellcheck disable=SC2120
function buildLibAom() {
  local ABI
  local ABIS="${1:-$ANDROID_ABIS}"
  pushd $AOM_DIR
  for ABI in $ABIS; do
  {
    local CMAKE_BUILD_DIR=$AOM_DIR/aom_build_${ABI}
    rm -rf ${CMAKE_BUILD_DIR}
    mkdir -p ${CMAKE_BUILD_DIR}
    cd ${CMAKE_BUILD_DIR}

    ${CMAKE_EXECUTABLE} .. \
      -DCMAKE_BUILD_TYPE=Release \
      -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
      -DANDROID_ABI=$ABI \
      -DCMAKE_TOOLCHAIN_FILE=${BASE_DIR}/Cmake/libaom/android.cmake \
      -DCMAKE_INSTALL_PREFIX=${AOM_OUT_DIR}/$ABI \
      -DCONFIG_AV1_ENCODER=0 \
      -DENABLE_DOCS=0 \
      -DENABLE_TESTS=0 \
      -DCONFIG_RUNTIME_CPU_DETECT=0 \
      -DCONFIG_WEBM_IO=0 \
      -DENABLE_EXAMPLES=0 \
      -DCONFIG_REALTIME_ONLY=1 \
      -DENABLE_TOOLS=0

    make -j$JOBS
    make install
  } &
  done
  wait
  popd
}

# shellcheck disable=SC2120
function buildFfmpeg() {
  rm -rf ${BUILD_DIR}/temp
  local ABI
  # F_DIR="${2:-$FFMPEG_DIR}"
  local ABIS="${1:-$ANDROID_ABIS}"
  local F_DIR=$FFMPEG_DIR
  pushd $F_DIR  
  local COMMON_OPTIONS="--enable-pic"
  # Add enabled decoders to FFmpeg build configuration
  for decoder in $ENABLED_DECODERS; do
    COMMON_OPTIONS+=" --enable-decoder=${decoder}"
  done
  # local new_pkg_config_path=""
  # for ABI in $ABIS; do
  #   local temp="$ABI/lib/pkgconfig"
  #   new_pkg_config_path=$MEDTLS_OUT_DIR/$temp:$VPX_OUT_DIR/$temp:$AOM_OUT_DIR/$temp:$new_pkg_config_path
  # done
  # new_pkg_config_path=$(echo "$new_pkg_config_path" | sed 's/:$//')
  # # 更新 PKG_CONFIG_PATH 变量
  # export PKG_CONFIG_PATH="$new_pkg_config_path:$PKG_CONFIG_PATH"
  # Build FFmpeg for each architecture and platform
  for ABI in $ABIS; do
  {
    local TOOLCHAIN
    local CPU
    local ARCH
    local EXTRA_BUILD_CONFIGURATION_FLAGS=""
    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      TOOLCHAIN=armv7a-linux-androideabi21-
      CPU=armv7-a
      ARCH=arm
      EXTRA_BUILD_CONFIGURATION_FLAGS="--enable-neon"
      ;;
    arm64-v8a)
      TOOLCHAIN=aarch64-linux-android21-
      CPU=armv8-a
      ARCH=aarch64
      EXTRA_BUILD_CONFIGURATION_FLAGS="--enable-neon"
      ;;
    x86)
      TOOLCHAIN=i686-linux-android21-
      CPU=i686
      ARCH=i686
      EXTRA_BUILD_CONFIGURATION_FLAGS=--disable-asm
      ;;
    x86_64)
      TOOLCHAIN=x86_64-linux-android21-
      CPU=x86-64-v2
      ARCH=x86_64
      EXTRA_BUILD_CONFIGURATION_FLAGS="--x86asmexe=${TOOLCHAIN_PREFIX}/bin/yasm"
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    local temp="$ABI/lib/pkgconfig"
    local new_pkg_config_path=$MEDTLS_OUT_DIR/$temp:$VPX_OUT_DIR/$temp:$AOM_OUT_DIR/$temp:$PKG_CONFIG_PATH
    new_pkg_config_path=$(echo "$new_pkg_config_path" | sed 's/:$//')
    # 更新 PKG_CONFIG_PATH 变量
    # export PKG_CONFIG_PATH="$new_pkg_config_path:$PKG_CONFIG_PATH"
    local DEP_CFLAGS=""
    local DEP_LD_FLAGS=""  
    # Referencing dependencies without pkgconfig
    # local DEP_CFLAGS="-I$AOM_OUT_DIR/$ABI/include -I$VPX_OUT_DIR/$ABI/include -I$MEDTLS_OUT_DIR/$ABI/include"
    # local DEP_LD_FLAGS="-L$AOM_OUT_DIR/$ABI/lib -L$VPX_OUT_DIR/$ABI/lib -L$MEDTLS_OUT_DIR/$ABI/lib" 

    local CMAKE_BUILD_DIR=${SOURCES_DIR}/temp_ff/${FFMPEG_VERSION}/ffmpeg_build_${ABI}
    if [[ ! -d "$CMAKE_BUILD_DIR" ]]; then
      mkdir -p $CMAKE_BUILD_DIR
      cp -r ${FFMPEG_DIR}/* $CMAKE_BUILD_DIR
    fi
    cd ${CMAKE_BUILD_DIR}
    # Configure FFmpeg build
    env PKG_CONFIG_PATH=$new_pkg_config_path \
        cc="${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang" \
        cxx="${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang++" \
        ar=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
        strip=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
        nm=${TOOLCHAIN_PREFIX}/bin/llvm-nm \
        ranlib="${TOOLCHAIN_PREFIX}/bin/llvm-ranlib" \
    ./configure \
      --prefix=$BUILD_DIR/temp/$ABI \
      --enable-cross-compile \
      --arch=$ARCH \
      --cpu=$CPU \
      --sysroot="${TOOLCHAIN_PREFIX}/sysroot" \
      --sysinclude="${TOOLCHAIN_PREFIX}/sysroot/usr/include" \
      --extra-cflags="-O3 -fPIC $DEP_CFLAGS" \
      --extra-ldflags="$DEP_LD_FLAGS" \
      --pkg-config="$(which pkg-config)" \
      --pkg-config-flags="--static" \
      --target-os=android \
      --enable-shared \
      --disable-static \
      --disable-doc \
      --disable-programs \
      --disable-everything \
      --disable-vulkan \
      --disable-avdevice \
      --disable-avformat \
      --disable-postproc \
      --disable-avfilter \
      --disable-symver \
      --enable-parsers \
      --enable-demuxers \
      --enable-swresample \
      --enable-avformat \
      --enable-libvpx \
      --enable-libaom \
      --enable-protocol=file,http,https,mmsh,mmst,pipe,rtmp,rtmps,rtmpt,rtmpts,rtp,tls \
      --enable-version3 \
      --enable-mbedtls \
      --extra-ldexeflags=-pie \
      --disable-debug \
      ${EXTRA_BUILD_CONFIGURATION_FLAGS} \
      $COMMON_OPTIONS

    # Build FFmpeg
    echo "Building FFmpeg for $ARCH..."
    make clean
    make -j$JOBS
    make install

    local OUTPUT_LIB=${OUTPUT_DIR}/lib/${ABI}
    mkdir -p "${OUTPUT_LIB}"
    cp "${BUILD_DIR}"/temp/"${ABI}"/lib/*.so "${OUTPUT_LIB}"

    local OUTPUT_HEADERS=${OUTPUT_DIR}/include/${ABI}
    mkdir -p "${OUTPUT_HEADERS}"
    cp -r "${BUILD_DIR}"/temp/"${ABI}"/include/* "${OUTPUT_HEADERS}"
  } &
  done
  wait
  popd
}

#if [[ ! -d "$OUTPUT_DIR" && ! -d "$BUILD_DIR" ]]; then
if [[ ! -d "$OUTPUT_DIR" ]]; then
  # Download MbedTLS source code if it doesn't exist
  if [[ ! -d "$MBEDTLS_DIR" ]]; then
    downloadMbedTLS
  fi

  # Download Vpx source code if it doesn't exist
  if [[ ! -d "$VPX_DIR" ]]; then
    downloadLibVpx
  fi

  if [[ ! -d "$AOM_DIR" ]]; then
    downloadLibAom
  fi

  # Download Ffmpeg source code if it doesn't exist
  if [[ ! -d "$FFMPEG_DIR" ]]; then
    downloadFfmpeg
  fi
  
  #Building library
  if [[ ! -d "$MEDTLS_OUT_DIR" ]]; then
    buildMbedTLS &
  fi 
  if [[ ! -d "$VPX_OUT_DIR" ]]; then
    buildLibVpx &
  fi
  if [[ ! -d "$AOM_OUT_DIR" ]]; then
    buildLibAom &
  fi
  wait
  buildFfmpeg
fi
