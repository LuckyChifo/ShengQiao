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
target_compile_options(shengqiao_opus PRIVATE -Wall -Wextra -Werror)
