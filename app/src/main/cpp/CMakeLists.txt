cmake_minimum_required(VERSION 3.22.1)

project("bodyslidecore")

# nifly is a static library for NIF parsing/writing (Skyrim LE meshes).
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/nifly EXCLUDE_FROM_ALL)

add_library(
        bodyslidecore
        SHARED
        native-lib.cpp
        bs_build.cpp
        third_party/tinyxml2/tinyxml2.cpp
)

find_library(log-lib log)

target_include_directories(bodyslidecore PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/tinyxml2
)

target_link_libraries(
        bodyslidecore
        nifly
        ${log-lib}
)

target_compile_features(bodyslidecore PRIVATE cxx_std_17)
