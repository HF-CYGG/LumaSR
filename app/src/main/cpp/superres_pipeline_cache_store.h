#pragma once

#include <cstddef>
#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

std::string superres_sha256_hex(std::string_view value);
std::string superres_sha256_hex(const unsigned char* data, std::size_t size);
bool is_valid_superres_pipeline_cache_key(const std::string& key);

class SuperResPipelineCacheStore {
public:
    SuperResPipelineCacheStore(
        std::filesystem::path root,
        std::size_t maximumEntryBytes = 8u * 1024u * 1024u,
        std::size_t maximumTotalBytes = 32u * 1024u * 1024u
    );

    bool configured() const;
    std::filesystem::path cache_path(const std::string& key) const;
    std::vector<unsigned char> load(const std::string& key) const;
    bool save(const std::string& key, const std::vector<unsigned char>& data);
    void remove(const std::string& key) const;

private:
    void maintain(const std::string& protectedKey);

    std::filesystem::path root_;
    std::size_t maximumEntryBytes_;
    std::size_t maximumTotalBytes_;
    bool configured_ = false;
};
