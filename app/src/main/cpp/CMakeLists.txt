cmake_minimum_required(VERSION 3.22.1)

project("hermes-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# LLAMA_CPP_PATH must be passed via Gradle (read from local.properties:
#   hermes.ondevice.llamaCppPath=/absolute/path/to/llama.cpp)
if(NOT DEFINED LLAMA_CPP_PATH)
    message(FATAL_ERROR
        "LLAMA_CPP_PATH is not defined.\n"
        "Add to hermes.local.properties:\n"
        "  hermes.ondevice.llamaCppPath=/path/to/llama.cpp\n"
        "Then enable native build with:\n"
        "  hermes.ondevice.enabled=true")
endif()

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

add_subdirectory(${LLAMA_CPP_PATH} build-llama)

add_library(hermes-llama SHARED ai_chat.cpp)

target_include_directories(hermes-llama PRIVATE
        ${LLAMA_CPP_PATH}
        ${LLAMA_CPP_PATH}/common
        ${LLAMA_CPP_PATH}/include
        ${LLAMA_CPP_PATH}/ggml/include
        ${LLAMA_CPP_PATH}/ggml/src)

target_link_libraries(hermes-llama
        llama
        llama-common
        android
        log)
