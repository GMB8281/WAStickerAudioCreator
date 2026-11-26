cmake_minimum_required(VERSION 3.22.1)
project(wa_bridge CXX)

set(RUST_LIB ${CMAKE_CURRENT_SOURCE_DIR}/rust/prebuilt/${ANDROID_ABI}/libwa_core.a)
if(NOT EXISTS ${RUST_LIB})
    message(FATAL_ERROR "libwa_core.a missing for ${ANDROID_ABI}. Run ./build-rust.sh first.")
endif()

add_library(wa_core STATIC IMPORTED)
set_target_properties(wa_core PROPERTIES IMPORTED_LOCATION ${RUST_LIB})

add_library(wa_bridge SHARED native-bridge.cpp)
target_link_libraries(wa_bridge wa_core log)
