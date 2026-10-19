cmake_minimum_required(VERSION 3.22)
project(imci_jni C CXX)

# app/src/main/cpp -> whisper.cpp git submodule in the repository root (pinned to the tested commit)
set(WHISPER_ROOT ${CMAKE_SOURCE_DIR}/../../../../whisper.cpp)

set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
# One APK for every phone: build all Android CPU variants
# (armv8.0 for Redmi Note 7 ... armv8.6/9.x with dotprod+i8mm for Poco F5);
# ggml_backend_load_all_from_path() picks the best one at runtime.
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} whisper_build)

add_library(imci_jni SHARED imci_jni.c)
target_link_libraries(imci_jni whisper ggml ggml-base log android)
