# Whisper.cpp JNI for ADay — fetches whisper sources at configure time (needs network on first CMake run).
cmake_minimum_required(VERSION 3.22)
project(aday_whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)
FetchContent_Declare(
    whisper_cpp_src
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(whisper_cpp_src)
if (NOT whisper_cpp_src_POPULATED)
    FetchContent_Populate(whisper_cpp_src)
endif ()
set(WHISPER_LIB_DIR ${whisper_cpp_src_SOURCE_DIR})
message(STATUS "WHISPER_LIB_DIR=${WHISPER_LIB_DIR}")

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else ()
    set(WHISPER_VERSION "unknown")
endif ()
message(STATUS "Whisper version string: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

# 16 KB page-size ELF alignment (Play / Android 15+).
# Must touch CMAKE_*_LINKER_FLAGS so targets inside add_subdirectory(ggml) inherit the flag;
# add_link_options alone does not reliably apply to those shared libs.
# NDK r28 + ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES in Gradle is still required.
if (ANDROID)
    string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
    # Do not link NDK prebuilt libomp (bundled as libomp.so; often 4 KB ELF alignment). GGML uses pthreads without OpenMP.
    set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)
endif ()

# Build ggml from the same tree as whisper.cpp (FetchContent + add_subdirectory avoids
# FetchContent_MakeAvailable(ggml) breaking Android NDK CMAKE_C_* cache on some AGP versions).
add_subdirectory(${WHISPER_LIB_DIR}/ggml ${CMAKE_BINARY_DIR}/ggml_build)

add_library(whisper SHARED ${SOURCE_FILES})

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
    target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(whisper PRIVATE -flto)
endif ()

target_link_libraries(whisper ${LOG_LIB} android ggml)

target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)
