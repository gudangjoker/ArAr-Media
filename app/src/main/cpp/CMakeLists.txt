cmake_minimum_required(VERSION 3.22.1)

project("arar_media_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(-O3 -ffast-math)

add_library(
    arar_media_engine
    SHARED
    native_media_engine.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    arar_media_engine
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
