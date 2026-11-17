cmake_minimum_required(VERSION 3.22.1)
project(vinlagon_native LANGUAGES CXX)

add_library(vinlagon_native SHARED native_bridge.cpp)
target_compile_features(vinlagon_native PRIVATE cxx_std_17)
target_link_options(vinlagon_native PRIVATE "-Wl,-z,max-page-size=16384")
target_compile_options(vinlagon_native PRIVATE -Wall -Wextra -Werror)
