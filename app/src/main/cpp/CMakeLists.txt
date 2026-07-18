cmake_minimum_required(VERSION 3.22.1)
project("exec_vpn")

add_library(
        exec_vpn
        SHARED
        exec_vpn.cpp)

target_link_libraries(
        exec_vpn
        log)
