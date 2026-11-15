cmake_minimum_required(VERSION 3.22.1)
project(shengqiao_opus C)

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL OFF CACHE BOOL "" FORCE)
set(OPUS_PACKAGE_VERSION "1.5.2")
add_subdirectory(opus)
target_compile_options(opus PRIVATE $<$<CONFIG:Debug>:-O2>)

add_library(shengqiao_opus SHARED opus_jni.c)
target_link_libraries(shengqiao_opus PRIVATE opus)
# NDK r27 needs explicit ELF alignment for Android devices with 16 KiB pages.
target_link_options(shengqiao_opus PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_compile_options(shengqiao_opus PRIVATE -Wall -Wextra -Werror)
