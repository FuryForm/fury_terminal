cmake_minimum_required(VERSION 3.18)
project(term LANGUAGES C)

# --- Shared library: libterm.so (JNI bridge for the Android app) ---
add_library(term SHARED libterm.c)
target_link_libraries(term PRIVATE log)
set_target_properties(term PROPERTIES
    C_STANDARD 11
    POSITION_INDEPENDENT_CODE ON
)

# --- Executable: ftyd (root PTY daemon) ---
# Only built when explicitly requested or via standalone CMake invocation.
# Gradle's externalNativeBuild only picks up SHARED/MODULE libraries,
# so this won't interfere with the library build.
option(BUILD_DAEMON "Build the ftyd daemon executable" OFF)

if(BUILD_DAEMON)
    add_executable(ftyd ftyd.c)
    target_link_libraries(ftyd PRIVATE log)
    set_target_properties(ftyd PROPERTIES
        C_STANDARD 11
    )
    # Dynamic linking — liblog.so is always available on Android
    # Static linking not used because NDK only ships liblog as shared
endif()
