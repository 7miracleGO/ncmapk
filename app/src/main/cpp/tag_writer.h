#ifndef NCMMusicTag_TAG_WRITER_H
#define NCMMusicTag_TAG_WRITER_H

#include <string>
#include <vector>

bool writeMetadata(const std::string& filePath,
                   const std::string& title,
                   const std::string& artist,
                   const std::string& album,
                   const std::vector<char>& coverArtData);

#endif //NCMMusicTag_TAG_WRITER_H
