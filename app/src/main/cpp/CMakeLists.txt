cmake_minimum_required(VERSION 3.22)
project(kgmdec CXX)

add_library(kgmdec SHARED kgmdec.cpp)
target_compile_options(kgmdec PRIVATE -Os -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(kgmdec ${log-lib} dl)
