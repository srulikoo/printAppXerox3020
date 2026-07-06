cmake_minimum_required(VERSION 3.22.1)
project("splprint")

add_library(
        qpdlencoder
        SHARED
        qpdl_encoder.cpp
)

target_compile_options(qpdlencoder PRIVATE -O2 -Wall)

find_library(log-lib log)
target_link_libraries(qpdlencoder ${log-lib})
