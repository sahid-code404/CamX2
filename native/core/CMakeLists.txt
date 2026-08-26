cmake_minimum_required(VERSION 3.22.1)
project(camx_core LANGUAGES CXX)

if(ANDROID)
    if(NOT "${ANDROID_PLATFORM}" STREQUAL "android-23")
        message(FATAL_ERROR
            "libcamx_core must compile against Android API 23 "
            "(ANDROID_PLATFORM=${ANDROID_PLATFORM})"
        )
    endif()
endif()

add_library(camx_core SHARED
    src/jni/camx_jni.cpp
    src/discovery/ndk_advertised_metadata.cpp
    src/discovery/android_ndk_advertised_metadata.cpp
    src/buffer/native_buffer_pool.cpp
    src/memory/android_owners_compile.cpp
    src/memory/resource_counters.cpp
    src/trace/native_trace_buffer.cpp
)

target_compile_features(camx_core PUBLIC cxx_std_20)
target_include_directories(camx_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_options(camx_core PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_options(camx_core PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(camx_core PRIVATE dl)

# libcamx_core remains the API-23-loadable baseline. Camera-NDK metadata is
# resolved from the public libcamera2ndk.so only at runtime on API 24+; there is
# intentionally no Camera-NDK DT_NEEDED dependency here.
