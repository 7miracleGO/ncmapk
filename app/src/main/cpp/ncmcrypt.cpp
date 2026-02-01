#include "ncmcrypt.h"
#include "aes.h"
#include "base64.h"
#include "cJSON.h"
#include "color.h"

#include <stdexcept>
#include <string>
#include <filesystem>
#include <fstream>
#include <vector>
#include <iostream>
#include <cstring>

#pragma warning(disable:4267)
#pragma warning(disable:4244)

const unsigned char NeteaseCrypt::sCoreKey[17] = {0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57, 0};
const unsigned char NeteaseCrypt::sModifyKey[17] = {0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21, 0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28, 0};
const unsigned char NeteaseCrypt::mPng[8] = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

static void aesEcbDecrypt(const unsigned char *key, std::string &src, std::string &dst) {
    int i, n;
    unsigned char out[16];
    n = src.length() >> 4;
    dst.clear();
    AES aes(key);
    for (i = 0; i < n - 1; i++) {
        aes.decrypt((unsigned char *)src.c_str() + (i << 4), out);
        dst += std::string((char *)out, 16);
    }
    aes.decrypt((unsigned char *)src.c_str() + (i << 4), out);
    char pad = out[15];
    if (pad > 16) pad = 0;
    dst += std::string((char *)out, 16 - pad);
}

NeteaseMusicMetadata::~NeteaseMusicMetadata() {
    if (mRaw) cJSON_Delete(mRaw);
}

NeteaseMusicMetadata::NeteaseMusicMetadata(cJSON *raw) {
    if (!raw) return;
    cJSON *swap;
    int artistLen, i;
    mRaw = raw;
    swap = cJSON_GetObjectItem(raw, "musicName");
    if (swap) mName = std::string(cJSON_GetStringValue(swap));
    swap = cJSON_GetObjectItem(raw, "album");
    if (swap) mAlbum = std::string(cJSON_GetStringValue(swap));
    swap = cJSON_GetObjectItem(raw, "artist");
    if (swap) {
        artistLen = cJSON_GetArraySize(swap);
        for (i = 0; i < artistLen; i++) {
            auto artist = cJSON_GetArrayItem(swap, i);
            if (cJSON_GetArraySize(artist) > 0) {
                if (!mArtist.empty()) mArtist += "/";
                mArtist += std::string(cJSON_GetStringValue(cJSON_GetArrayItem(artist, 0)));
            }
        }
    }
}

bool NeteaseCrypt::openFile(std::string const &path) {
    mFile.open(std::filesystem::u8path(path), std::ios::in | std::ios::binary);
    return mFile.is_open();
}

bool NeteaseCrypt::isNcmFile() {
    unsigned int header;
    mFile.read(reinterpret_cast<char *>(&header), sizeof(header));
    if (header != (unsigned int)0x4e455443) return false;
    mFile.read(reinterpret_cast<char *>(&header), sizeof(header));
    if (header != (unsigned int)0x4d414446) return false;
    return true;
}

int NeteaseCrypt::read(char *s, std::streamsize n) {
    mFile.read(s, n);
    int gcount = mFile.gcount();
    if (gcount <= 0) throw std::invalid_argument("Can't read file");
    return gcount;
}

void NeteaseCrypt::buildKeyBox(unsigned char *key, int keyLen) {
    int i;
    for (i = 0; i < 256; ++i) mKeyBox[i] = (unsigned char)i;
    unsigned char swap = 0, c = 0, last_byte = 0, key_offset = 0;
    for (i = 0; i < 256; ++i) {
        swap = mKeyBox[i];
        c = ((swap + last_byte + key[key_offset++]) & 0xff);
        if (key_offset >= keyLen) key_offset = 0;
        mKeyBox[i] = mKeyBox[c];
        mKeyBox[c] = swap;
        last_byte = c;
    }
}

std::string NeteaseCrypt::mimeType(std::string &data) {
    if (memcmp(data.c_str(), mPng, 8) == 0) return std::string("image/png");
    return std::string("image/jpeg");
}

void NeteaseCrypt::FixMetadata() {
    // Android版暂时不处理歌曲封面和信息
    return;
}

void NeteaseCrypt::Dump(std::string const &outputDir) {
    if (outputDir.empty()) {
        mDumpFilepath = std::filesystem::u8path(mFilepath);
    } else {
        mDumpFilepath = std::filesystem::u8path(outputDir) / std::filesystem::u8path(mFilepath).filename();
    }

    std::vector<unsigned char> buffer(0x8000);
    std::ofstream output;

    while (!mFile.eof()) {
        int n;
        try { n = read((char *)buffer.data(), buffer.size()); }
        catch (...) { break; }

        for (int i = 0; i < n; i++) {
            int j = (i + 1) & 0xff;
            buffer[i] ^= mKeyBox[(mKeyBox[j] + mKeyBox[(mKeyBox[j] + j) & 0xff]) & 0xff];
        }

        if (!output.is_open()) {
            if (buffer[0] == 0x49 && buffer[1] == 0x44 && buffer[2] == 0x33) {
                mDumpFilepath.replace_extension(".mp3");
                mFormat = NeteaseCrypt::MP3;
            } else {
                mDumpFilepath.replace_extension(".flac");
                mFormat = NeteaseCrypt::FLAC;
            }
            output.open(mDumpFilepath, std::ofstream::out | std::ofstream::binary);
        }
        output.write((char *)buffer.data(), n);
    }
    output.flush();
    output.close();
}

NeteaseCrypt::~NeteaseCrypt() {
    if (mMetaData != NULL) delete mMetaData;
    mFile.close();
}

NeteaseCrypt::NeteaseCrypt(std::string const &path) {
    if (!openFile(path)) throw std::invalid_argument("Can't open file");
    if (!isNcmFile()) throw std::invalid_argument("Not netease protected file");
    if (!mFile.seekg(2, mFile.cur)) throw std::invalid_argument("Can't seek file");

    mFilepath = path;
    unsigned int n;
    read(reinterpret_cast<char *>(&n), sizeof(n));
    if (n <= 0) throw std::invalid_argument("Broken NCM file");

    std::vector<char> keydata(n);
    read(keydata.data(), n);
    for (size_t i = 0; i < n; i++) keydata[i] ^= 0x64;

    std::string rawKeyData(keydata.begin(), keydata.end());
    std::string mKeyData;
    aesEcbDecrypt(sCoreKey, rawKeyData, mKeyData);
    buildKeyBox((unsigned char *)mKeyData.c_str() + 17, mKeyData.length() - 17);

    read(reinterpret_cast<char *>(&n), sizeof(n));
    if (n <= 0) {
        mMetaData = NULL;
    } else {
        std::vector<char> modifyData(n);
        read(modifyData.data(), n);
        for (size_t i = 0; i < n; i++) modifyData[i] ^= 0x63;
        std::string swapModifyData = std::string(modifyData.begin() + 22, modifyData.end());
        std::string modifyOutData, modifyDecryptData;
        Base64::Decode(swapModifyData, modifyOutData);
        aesEcbDecrypt(sModifyKey, modifyOutData, modifyDecryptData);
        modifyDecryptData = std::string(modifyDecryptData.begin() + 6, modifyDecryptData.end());
        mMetaData = new NeteaseMusicMetadata(cJSON_Parse(modifyDecryptData.c_str()));
    }

    if (!mFile.seekg(5, mFile.cur)) throw std::invalid_argument("can't seek file");
    uint32_t cover_frame_len{0};
    read(reinterpret_cast<char *>(&cover_frame_len), 4);
    read(reinterpret_cast<char *>(&n), sizeof(n));
    if (n > 0) {
        mImageData = std::string(n, '\0');
        read(&mImageData[0], n);
    }
    mFile.seekg(cover_frame_len - n, mFile.cur);
}

// --- 添加缺失的函数实现 ---
std::string NeteaseCrypt::GetMetadataJson() const {
    if (mMetaData && mMetaData->raw()) {
        char* json_str = cJSON_PrintUnformatted(mMetaData->raw());
        if (json_str) {
            std::string result(json_str);
            cJSON_free(json_str);
            return result;
        }
    }
    return "{}"; // 返回一个空的JSON对象
}

std::string NeteaseCrypt::GetDumpPath() const {
    return mDumpFilepath.u8string();
}
