cmake_minimum_required(VERSION 3.22.1)
project("yggdrasilmanager")

set(UNWINDSTACK_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libunwindstack-ndk)
include_directories(${UNWINDSTACK_ROOT} ${UNWINDSTACK_ROOT}/include)
add_subdirectory(${UNWINDSTACK_ROOT}/cmake unwindstack-build)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ygg_jni.cpp
        ygg_backtrace.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${UNWINDSTACK_ROOT}
        ${UNWINDSTACK_ROOT}/include)

target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_17)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        unwindstack)