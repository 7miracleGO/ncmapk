#include "tag_writer.h"
#include <iostream>

// --- TagLib Includes (修正后) ---
#include <tag.h>
#include <fileref.h>
#include <tpropertymap.h>
#include <mpegfile.h>
#include <id3v2tag.h>
#include <id3v2frame.h>
#include <attachedpictureframe.h>
#include <flacfile.h>
#include <vorbisproperties.h>
#include <xiphcomment.h>


// Helper function to check file extension
bool hasExtension(const std::string& filePath, const std::string& ext) {
    if (filePath.length() >= ext.length()) {
        return (0 == filePath.compare(filePath.length() - ext.length(), ext.length(), ext));
    } else {
        return false;
    }
}

bool writeMetadata(const std::string& filePath,
                   const std::string& title,
                   const std::string& artist,
                   const std::string& album,
                   const std::vector<char>& coverArtData) {

    TagLib::FileRef f(filePath.c_str(), false);

    if (f.isNull() || !f.tag()) {
        std::cerr << "TagLib: Could not open file or find tag for " << filePath << std::endl;
        return false;
    }

    TagLib::Tag *tag = f.tag();
    tag->setTitle(TagLib::String(title, TagLib::String::UTF8));
    tag->setArtist(TagLib::String(artist, TagLib::String::UTF8));
    tag->setAlbum(TagLib::String(album, TagLib::String::UTF8));

    // --- Cover Art Handling ---
    if (!coverArtData.empty()) {
        if (hasExtension(filePath, ".mp3")) {
            TagLib::MPEG::File* mpegFile = dynamic_cast<TagLib::MPEG::File*>(f.file());
            if (mpegFile) {
                TagLib::ID3v2::Tag *id3v2tag = mpegFile->ID3v2Tag(true);
                if (id3v2tag) {
                    id3v2tag->removeFrames("APIC");
                    TagLib::ID3v2::AttachedPictureFrame *frame = new TagLib::ID3v2::AttachedPictureFrame();
                    frame->setMimeType("image/jpeg");
                    frame->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
                    frame->setPicture(TagLib::ByteVector(coverArtData.data(), coverArtData.size()));
                    id3v2tag->addFrame(frame);
                }
            }
        }
        else if (hasExtension(filePath, ".flac")) {
            TagLib::FLAC::File* flacFile = dynamic_cast<TagLib::FLAC::File*>(f.file());
            if (flacFile) {
                flacFile->removePictures();
                TagLib::FLAC::Picture *pic = new TagLib::FLAC::Picture();
                pic->setMimeType("image/jpeg");
                pic->setType(TagLib::FLAC::Picture::FrontCover);
                pic->setData(TagLib::ByteVector(coverArtData.data(), coverArtData.size()));
                flacFile->addPicture(pic);
            }
        }
    }

    return f.file()->save();
}
