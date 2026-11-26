cmake_minimum_required(VERSION 3.22.1)
project(paperpages_vk LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(GLSLC_CANDIDATE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
if(WIN32)
    set(GLSLC_CANDIDATE "${GLSLC_CANDIDATE}.exe")
endif()
if(EXISTS "${GLSLC_CANDIDATE}")
    set(GLSLC_EXECUTABLE "${GLSLC_CANDIDATE}")
else()
    find_program(GLSLC_EXECUTABLE NAMES glslc REQUIRED)
endif()

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

function(compile_spv_inc SHADER_SRC OUT_NAME STAGE)
    set(OUT_FILE "${GENERATED_SHADER_DIR}/${OUT_NAME}")
    add_custom_command(
        OUTPUT "${OUT_FILE}"
        COMMAND "${GLSLC_EXECUTABLE}" -mfmt=c "-fshader-stage=${STAGE}" "${SHADER_SRC}" -o "${OUT_FILE}"
        DEPENDS "${SHADER_SRC}"
        COMMENT "Compiling ${SHADER_SRC} to SPIR-V"
        VERBATIM
    )
    list(APPEND GENERATED_SHADER_HEADERS "${OUT_FILE}")
    set(GENERATED_SHADER_HEADERS "${GENERATED_SHADER_HEADERS}" PARENT_SCOPE)
endfunction()

compile_spv_inc("${CMAKE_CURRENT_SOURCE_DIR}/shaders/wallpaper.vert" "wallpaper_vert_spv.inc" "vert")
compile_spv_inc("${CMAKE_CURRENT_SOURCE_DIR}/shaders/wallpaper.frag" "wallpaper_frag_spv.inc" "frag")
compile_spv_inc("${CMAKE_CURRENT_SOURCE_DIR}/shaders/motion_blur.vert" "motion_blur_vert_spv.inc" "vert")
compile_spv_inc("${CMAKE_CURRENT_SOURCE_DIR}/shaders/motion_blur.frag" "motion_blur_frag_spv.inc" "frag")

add_library(
    paperpages_vk
    SHARED
    vk_context.cpp
    vk_swapchain.cpp
    vk_renderer_jni.cpp
    ${GENERATED_SHADER_HEADERS}
)

target_include_directories(
    paperpages_vk
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${GENERATED_SHADER_DIR}"
)

target_compile_options(
    paperpages_vk
    PRIVATE
    -O2
    -Wall
    -Wextra
)

target_link_libraries(
    paperpages_vk
    vulkan
    android
    jnigraphics
    log
)
