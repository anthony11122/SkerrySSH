# Native PTY helper for the Android local shell. forkpty/login_tty live in bionic's libc, so the
# only extra link dependency is the log library (error reporting during device bring-up).
cmake_minimum_required(VERSION 3.22.1)
project(skerrypty C)

add_library(skerrypty SHARED skerry_pty.c)

# 16 KB ELF alignment: required by Google Play (Nov 2025) and to load at all on 16 KB-page devices.
# NDK r27 does not default to it, so ask the linker for 16 KB-aligned LOAD segments explicitly.
target_link_options(skerrypty PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(skerrypty ${log-lib})
