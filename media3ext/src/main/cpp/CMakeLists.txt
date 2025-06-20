# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project.

project(media3ext C CXX)
string(TOLOWER "${CMAKE_BUILD_TYPE}" build_type)
if(build_type MATCHES "^rel")
    add_compile_options("-O2")
endif()
set(ffmpeg_dir ${CMAKE_SOURCE_DIR}/../../../../ffmpeg/output)
set(ffmpeg_libs ${ffmpeg_dir}/lib/${ANDROID_ABI})

include_directories(${ffmpeg_dir}/include/${ANDROID_ABI})

set(
        # List variable name
        ffmpeg_libs_names
        # Values in the list
        avutil avcodec swresample swscale)

foreach (ffmpeg_lib_name ${ffmpeg_libs_names})
    add_library(
            ${ffmpeg_lib_name}
            SHARED
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib_name}
            PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_libs}/lib${ffmpeg_lib_name}.so)
endforeach ()

add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        ffmain.cpp
        ffcommon.cpp
        ffaudio.cpp
        ffvideo.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        PRIVATE log
        PRIVATE android
        PRIVATE ${ffmpeg_libs_names})
target_link_options(${CMAKE_PROJECT_NAME}
        PRIVATE "-Wl,-z,max-page-size=16384")