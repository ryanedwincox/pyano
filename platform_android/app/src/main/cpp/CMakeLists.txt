cmake_minimum_required(VERSION 3.22.1)
project(pyano-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Fetch Oboe for low-latency audio
include(FetchContent)
FetchContent_Declare(oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# FluidSynth — use prebuilt libs from jniLibs
set(FLUIDSYNTH_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../fluidsynth-libs/${ANDROID_ABI})

add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
    IMPORTED_LOCATION ${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so
)

# Include FluidSynth headers
set(FLUIDSYNTH_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../../../../fluidsynth-libs/include)

# Our native library
add_library(pyano-native SHARED
    native-lib.c
    audio-engine.cpp
)

target_include_directories(pyano-native PRIVATE
    ${FLUIDSYNTH_INCLUDE_DIR}
)

target_link_libraries(pyano-native
    fluidsynth
    oboe
    android
    log
)
