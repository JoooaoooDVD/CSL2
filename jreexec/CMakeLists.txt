cmake_minimum_required(VERSION 3.22.1)

project(jreexec DESCRIPTION "Android porting utility library")

add_library(jreexec SHARED
        vulkan_loader.c
        jreexec.c
        driver_helper/nsbypass.c
        driver_helper/fake_dlfcn.c
        driver_helper/func_locator.c)

target_include_directories(jreexec PRIVATE ".")
target_include_directories(jreexec PUBLIC include)

target_link_libraries(jreexec PRIVATE log)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_sources(jreexec PRIVATE driver_helper/arm64_func_locator.c)
    target_compile_definitions(jreexec PRIVATE USE_ARM64_LOCATOR)
    target_compile_definitions(jreexec PRIVATE ENABLE_TURNIP_LOADER)

    add_library(linkerhook SHARED
            driver_helper/hook.c
    )
    target_link_options(linkerhook PRIVATE "-Wl,-z,global")
endif()

target_link_options(jreexec PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/version.script")

set_target_properties(jreexec PROPERTIES
        LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/version.script"
)