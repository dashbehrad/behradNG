cmake_minimum_required(VERSION 3.10)
project(alandpi C)

set(CMAKE_C_STANDARD 99)

add_library(alandpi STATIC
    alandpi.c
    alandpi.h
)

target_include_directories(alandpi PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(BUILD_SHARED_LIBS)
    add_library(alandpi_shared SHARED
        alandpi.c
        alandpi.h
    )
    set_target_properties(alandpi_shared PROPERTIES OUTPUT_NAME "alandpi")
endif()

