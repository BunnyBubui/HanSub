cmake_minimum_required(VERSION 3.22)
project(hansub_native LANGUAGES C CXX)

# The on-device translator (Hy-MT2) runs on llama.cpp. Only 64-bit ARM is built; the Java side
# treats a missing library as "this phone cannot run the local translator".
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "hansub_llm: skipped for ABI ${ANDROID_ABI}")
    return()
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is not copied into this repository. It is fetched once at a pinned commit, so every build uses the
# same code that llm_jni.cpp was written against. Delete app/src/main/cpp/llama.cpp to fetch again.
set(LLAMA_COMMIT "8a97fa98ab1a454709bbf5ac979661feaeadec40")
set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    find_package(Git REQUIRED)
    file(MAKE_DIRECTORY "${LLAMA_DIR}")
    foreach(step
            "init"
            "fetch;--depth;1;https://github.com/ggml-org/llama.cpp.git;${LLAMA_COMMIT}"
            "checkout;--quiet;FETCH_HEAD")
        execute_process(COMMAND "${GIT_EXECUTABLE}" ${step}
                WORKING_DIRECTORY "${LLAMA_DIR}"
                RESULT_VARIABLE git_result
                ERROR_VARIABLE git_error)
        if(NOT git_result EQUAL 0)
            message(FATAL_ERROR "Could not fetch llama.cpp (git ${step}): ${git_error}")
        endif()
    endforeach()
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Only the library: no tools, tests, examples, server or HTTPS client.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

# CPU code is built in several variants (plain ARMv8, dot-product, i8mm ...) and the best one is picked at run time,
# so one APK works on old and new phones. They are loaded from the app's native library folder.
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama-build)

add_library(hansub_llm SHARED llm_jni.cpp)
target_link_libraries(hansub_llm PRIVATE llama android log)
