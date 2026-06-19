cmake_minimum_required(VERSION 3.22.1)

project(localsr)

if(NOT DEFINED NCNN_ROOT)
    set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.deps/native/ncnn")
endif()

add_library(localsr SHARED
    superres_jni.cpp
    superres_engine.cpp
)

target_compile_features(localsr PRIVATE cxx_std_17)
target_include_directories(localsr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../.deps/sources/waifu2x-ncnn-vulkan/src
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)
find_library(cxx-shared-lib c++_shared)

set(NCNN_ABI_ROOT "${NCNN_ROOT}/${ANDROID_ABI}")
if(EXISTS "${NCNN_ABI_ROOT}/include/ncnn/net.h" AND EXISTS "${NCNN_ABI_ROOT}/lib/libncnn.a")
    add_library(ncnn STATIC IMPORTED)
    set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_ABI_ROOT}/lib/libncnn.a")
    target_include_directories(localsr PRIVATE "${NCNN_ABI_ROOT}/include/ncnn")
    target_link_libraries(localsr PRIVATE
        ncnn
        ${vulkan-lib}
        ${jnigraphics-lib}
        -fopenmp
        -static-openmp
        dl
        "${NCNN_ABI_ROOT}/lib/libSPIRV.a"
        "${NCNN_ABI_ROOT}/lib/libglslang.a"
    )
else()
    message(FATAL_ERROR "ncnn Android Vulkan SDK not found for ${ANDROID_ABI}. Expected ${NCNN_ABI_ROOT}/include/ncnn and ${NCNN_ABI_ROOT}/lib/libncnn.a")
endif()

target_link_libraries(localsr PRIVATE ${log-lib} ${android-lib} ${cxx-shared-lib} z)
