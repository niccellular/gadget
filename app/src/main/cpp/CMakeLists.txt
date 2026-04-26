cmake_minimum_required(VERSION 3.18)
project(gadget-hooker)

add_library(hooker SHARED hooker.c)
target_link_libraries(hooker log)
