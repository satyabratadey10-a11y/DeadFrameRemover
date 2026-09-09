cmake_minimum_required(VERSION 3.22.1)
project("frame_comparator")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization without -ffast-math for precise integer arithmetic
add_compile_options(-O3 -Wall)

add_library(frame_comparator SHARED
    frame_comparator.cpp
)

find_library(log-lib log)

target_link_libraries(frame_comparator
    ${log-lib}
)
