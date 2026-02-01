#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h> // 必须引入日志库

#include "ncmcrypt.h"
#include "tag_writer.h"

// 定义日志宏
#define LOG_TAG "NCM_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JNI 字符串转换辅助函数
std::string jstringToString(JNIEnv *env, jstring jStr) {
    if (!jStr) return "";
    const char *chars = env->GetStringUTFChars(jStr, NULL);
    if (!chars) return "";
    std::string str = chars;
    env->ReleaseStringUTFChars(jStr, chars);
    return str;
}

// --- 公用的元数据提取函数 ---
std::string getMetadata(JNIEnv *env, jstring inputPath) {
    const char *nativeInputPath = env->GetStringUTFChars(inputPath, 0);
    if (nativeInputPath == nullptr) {
        return "{}";
    }

    std::string json_str = "{}";
    try {
        NeteaseCrypt crypt(nativeInputPath);
        json_str = crypt.GetMetadataJson();
    } catch (const std::exception& e) {
        LOGE("获取元数据时发生异常: %s", e.what());
    } catch (...) {
        LOGE("获取元数据时发生未知错误");
    }

    env->ReleaseStringUTFChars(inputPath, nativeInputPath);
    return json_str;
}

// --- 为 MainActivity 提供的 JNI 入口 ---
extern "C" JNIEXPORT jstring JNICALL
Java_com_ncm_converter_MainActivity_getNcmMetadata(JNIEnv *env, jobject, jstring inputPath) {
    std::string metadata = getMetadata(env, inputPath);
    return env->NewStringUTF(metadata.c_str());
}

// --- 为 FileListDialogFragment 提供的 JNI 入口 ---
extern "C" JNIEXPORT jstring JNICALL
Java_com_ncm_converter_FileListDialogFragment_getNcmMetadata(JNIEnv *env, jobject, jstring inputPath) {
    std::string metadata = getMetadata(env, inputPath);
    return env->NewStringUTF(metadata.c_str());
}


extern "C" JNIEXPORT jint JNICALL
Java_com_ncm_converter_MainActivity_unlockNcm(
        JNIEnv* env,
        jobject /* this */,
        jstring inputPath,
        jstring outputDir,
        jstring title,
        jstring artist,
        jstring album,
        jbyteArray coverArt
) {
    const char *nativeInputPath = env->GetStringUTFChars(inputPath, 0);
    const char *nativeOutputDir = env->GetStringUTFChars(outputDir, 0);

    if (nativeInputPath == nullptr || nativeOutputDir == nullptr) {
        return -2;
    }

    int result = 0;
    try {
        NeteaseCrypt crypt(nativeInputPath);
        crypt.Dump(nativeOutputDir);
        std::string dump_path = crypt.GetDumpPath();

        std::string titleStr = jstringToString(env, title);
        std::string artistStr = jstringToString(env, artist);
        std::string albumStr = jstringToString(env, album);

        std::vector<char> coverData;
        if (coverArt != NULL) {
            jsize len = env->GetArrayLength(coverArt);
            if (len > 0) {
                coverData.resize(len);
                env->GetByteArrayRegion(coverArt, 0, len, reinterpret_cast<jbyte*>(coverData.data()));
            }
        }

        bool tagWriteSuccess = writeMetadata(dump_path, titleStr, artistStr, albumStr, coverData);

        if(tagWriteSuccess) {
            result = 1;
        } else {
            result = -3;
        }

    } catch (const std::exception& e) {
        result = -1;
    } catch (...) {
        result = -99;
    }

    env->ReleaseStringUTFChars(inputPath, nativeInputPath);
    env->ReleaseStringUTFChars(outputDir, nativeOutputDir);

    return result;
}
