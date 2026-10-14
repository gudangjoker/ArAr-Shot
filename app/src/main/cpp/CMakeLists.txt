cmake_minimum_required(VERSION 3.22.1)
project("arar_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")

if(EXISTS "${NCNN_DIR}")
    message(STATUS "Found NCNN SDK at: ${NCNN_DIR}")
    set(ncnn_DIR "${NCNN_DIR}")
    find_package(ncnn REQUIRED)
    add_definitions(-DUSE_NCNN=1)
    set(USE_NCNN_ENABLED TRUE)
else()
    message(WARNING "NCNN SDK NOT found at ${NCNN_DIR}. Building arar_engine in safe fallback mode without NCNN.")
    set(USE_NCNN_ENABLED FALSE)
endif()

add_library(
    arar_engine
    SHARED
    native_enhancer.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_include_directories(
    arar_engine
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(USE_NCNN_ENABLED)
    target_link_libraries(
        arar_engine
        ncnn
        ${android-lib}
        ${log-lib}
        ${jnigraphics-lib}
    )
else()
    target_link_libraries(
        arar_engine
        ${android-lib}
        ${log-lib}
        ${jnigraphics-lib}
    )
endif()
