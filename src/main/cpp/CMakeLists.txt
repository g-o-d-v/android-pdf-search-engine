cmake_minimum_required(VERSION 3.22.1)

project(PdfSearchEngineNative LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PDF_SEARCH_NATIVE_SOURCES
        native.cpp
        ocr_clipper.cpp
        ocr_cls_process.cpp
        ocr_crnn_process.cpp
        ocr_db_post_process.cpp
        ocr_ppredictor.cpp
        pdfium_text_search.cpp
        ppredictor.cpp
        predictor_input.cpp
        predictor_output.cpp
        preprocess.cpp
)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/PaddleLite)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/OpenCV)

add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(
        paddle_light_api_shared
        PROPERTIES IMPORTED_LOCATION
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpaddle_light_api_shared.so"
)

add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(
        opencv_java4
        PROPERTIES IMPORTED_LOCATION
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_java4.so"
)

add_library(Native SHARED ${PDF_SEARCH_NATIVE_SOURCES})

# Keep compile settings target-local so they do not leak into imported SDKs.
target_compile_options(
        Native
        PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
        $<$<CONFIG:Release>:-O3>
)

# NDK r28 aligns shared libraries for 16 KiB pages by default. This project is
# still pinned to r21 for binary compatibility with the bundled OCR SDKs, so
# explicitly request compatible ELF LOAD segment alignment for libNative.so.
target_link_options(
        Native
        PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)

find_library(log-lib log)

target_link_libraries(
        Native
        paddle_light_api_shared
        opencv_java4
        GLESv2
        EGL
        jnigraphics
        dl
        ${log-lib}
)
