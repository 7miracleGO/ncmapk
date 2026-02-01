# 设置 CMake 最小版本要求
cmake_minimum_required(VERSION 3.22.1)

# 定义项目名称
project("ncmtest")

# --- 1. TagLib 源码编译配置 ---
set(BUILD_TESTS OFF)
set(BUILD_EXAMPLES OFF)
set(BUILD_SHARED_LIBS OFF)
set(WITH_ZLIB ON)

# --- 为 TagLib 和我们的项目提供全局头文件路径 (最终解决方案) ---
# TagLib 使用了过时的 include_directories() 命令, 不会将其内部路径传递出来。
# 我们必须在主项目里, 使用同样的 include_directories() 命令,
# 将所有路径全局性地提供给后续的所有目标 (包括 tag 和 ncmtest)。
include_directories(
    # a. 编译时生成的配置文件 (taglib_config.h)
    ${CMAKE_CURRENT_BINARY_DIR}/taglib

    # b. TagLib 的公共 API 头文件 (<taglib/tag.h>)
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib

    # c. TagLib 所有内部模块的头文件 (tstring.h, mpegfile.h, id3v2.h 等)
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/toolkit
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/mpeg
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/mpeg/id3v1
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/mpeg/id3v2
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/mpeg/id3v2/frames
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/ogg
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/ogg/vorbis
    ${CMAKE_CURRENT_SOURCE_DIR}/taglib/taglib/flac
)

# 将 taglib 源码目录添加为一个子项目
add_subdirectory(taglib)

# 查找 Android NDK 内置的 ZLIB 库
find_package(ZLIB REQUIRED)

# --- 2. 主项目配置 ---
# 定义我们自己的原生库 (ncmtest)
add_library(
        ncmtest
        SHARED
        native-lib.cpp
        ncmcrypt.cpp
        tag_writer.cpp
        lib/libncmdump.cpp
        utils/aes.cpp
        utils/cJSON.cpp
)

# 为 ncmtest 添加它自己的私有头文件。
# TagLib 的路径已经通过上面的全局 include_directories() 添加。
target_include_directories(ncmtest PRIVATE
        include
        lib
        utils
        platform
)

# 查找 Android 系统内置的日志库
find_library(log-lib log)

# --- 3. 链接所有库 ---
target_link_libraries(
        ncmtest
        tag
        ZLIB::ZLIB
        ${log-lib}
)
