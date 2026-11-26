cmake_minimum_required(VERSION 3.18.1)
project("kvm_native")

add_library(kvm_native SHARED kvm_native.c)
find_library(log-lib log)
target_link_libraries(kvm_native ${log-lib})
