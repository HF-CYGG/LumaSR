#include "superres_pipeline_cache_store.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <fstream>
#include <limits>
#include <system_error>

namespace {
constexpr char kEnvelopeMagic[] = "LUMASR_PIPELINE_CACHE_V1";
constexpr std::size_t kSha256BlockBytes = 64;
constexpr std::array<std::uint32_t, 64> kSha256RoundConstants = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

constexpr std::uint32_t rotate_right(std::uint32_t value, unsigned int amount) {
    return (value >> amount) | (value << (32u - amount));
}

std::array<unsigned char, 32> sha256(const unsigned char* data, std::size_t size) {
    std::array<std::uint32_t, 8> hash = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    };
    const std::size_t padding = (kSha256BlockBytes - ((size + 9u) % kSha256BlockBytes)) %
        kSha256BlockBytes;
    std::vector<unsigned char> message(size + 1u + padding + 8u, 0u);
    if (size > 0u && data != nullptr) {
        std::copy(data, data + size, message.begin());
    }
    message[size] = 0x80u;
    const std::uint64_t bitLength = static_cast<std::uint64_t>(size) * 8u;
    for (std::size_t index = 0; index < 8u; ++index) {
        message[message.size() - 1u - index] =
            static_cast<unsigned char>((bitLength >> (index * 8u)) & 0xffu);
    }
    for (std::size_t offset = 0; offset < message.size(); offset += kSha256BlockBytes) {
        std::array<std::uint32_t, 64> words{};
        for (std::size_t index = 0; index < 16u; ++index) {
            const std::size_t byte = offset + index * 4u;
            words[index] = (static_cast<std::uint32_t>(message[byte]) << 24u) |
                (static_cast<std::uint32_t>(message[byte + 1u]) << 16u) |
                (static_cast<std::uint32_t>(message[byte + 2u]) << 8u) |
                static_cast<std::uint32_t>(message[byte + 3u]);
        }
        for (std::size_t index = 16u; index < words.size(); ++index) {
            const std::uint32_t s0 = rotate_right(words[index - 15u], 7u) ^
                rotate_right(words[index - 15u], 18u) ^ (words[index - 15u] >> 3u);
            const std::uint32_t s1 = rotate_right(words[index - 2u], 17u) ^
                rotate_right(words[index - 2u], 19u) ^ (words[index - 2u] >> 10u);
            words[index] = words[index - 16u] + s0 + words[index - 7u] + s1;
        }
        std::uint32_t a = hash[0];
        std::uint32_t b = hash[1];
        std::uint32_t c = hash[2];
        std::uint32_t d = hash[3];
        std::uint32_t e = hash[4];
        std::uint32_t f = hash[5];
        std::uint32_t g = hash[6];
        std::uint32_t h = hash[7];
        for (std::size_t index = 0; index < words.size(); ++index) {
            const std::uint32_t sum1 = rotate_right(e, 6u) ^ rotate_right(e, 11u) ^
                rotate_right(e, 25u);
            const std::uint32_t choice = (e & f) ^ ((~e) & g);
            const std::uint32_t temp1 = h + sum1 + choice + kSha256RoundConstants[index] +
                words[index];
            const std::uint32_t sum0 = rotate_right(a, 2u) ^ rotate_right(a, 13u) ^
                rotate_right(a, 22u);
            const std::uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const std::uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        hash[0] += a;
        hash[1] += b;
        hash[2] += c;
        hash[3] += d;
        hash[4] += e;
        hash[5] += f;
        hash[6] += g;
        hash[7] += h;
    }
    std::array<unsigned char, 32> digest{};
    for (std::size_t index = 0; index < hash.size(); ++index) {
        digest[index * 4u] = static_cast<unsigned char>((hash[index] >> 24u) & 0xffu);
        digest[index * 4u + 1u] = static_cast<unsigned char>((hash[index] >> 16u) & 0xffu);
        digest[index * 4u + 2u] = static_cast<unsigned char>((hash[index] >> 8u) & 0xffu);
        digest[index * 4u + 3u] = static_cast<unsigned char>(hash[index] & 0xffu);
    }
    return digest;
}

std::string hex(const std::array<unsigned char, 32>& digest) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string encoded(64u, '0');
    for (std::size_t index = 0; index < digest.size(); ++index) {
        encoded[index * 2u] = kHex[(digest[index] >> 4u) & 0x0fu];
        encoded[index * 2u + 1u] = kHex[digest[index] & 0x0fu];
    }
    return encoded;
}

bool read_envelope(
    const std::filesystem::path& path,
    std::size_t maximumEntryBytes,
    std::vector<unsigned char>& payload
) {
    std::ifstream input(path, std::ios::binary);
    std::string magic;
    std::string sizeLine;
    std::string digest;
    if (!input || !std::getline(input, magic) || !std::getline(input, sizeLine) ||
        !std::getline(input, digest) || magic != kEnvelopeMagic || digest.size() != 64u) {
        return false;
    }
    std::size_t consumed = 0u;
    unsigned long long parsedSize = 0u;
    try {
        parsedSize = std::stoull(sizeLine, &consumed);
    } catch (...) {
        return false;
    }
    if (consumed != sizeLine.size() || parsedSize > maximumEntryBytes ||
        parsedSize > static_cast<unsigned long long>(std::numeric_limits<std::size_t>::max())) {
        return false;
    }
    payload.assign(static_cast<std::size_t>(parsedSize), 0u);
    if (!payload.empty()) {
        input.read(reinterpret_cast<char*>(payload.data()), static_cast<std::streamsize>(payload.size()));
    }
    char extra = 0;
    if (input.gcount() != static_cast<std::streamsize>(payload.size()) || input.get(extra) ||
        superres_sha256_hex(payload.data(), payload.size()) != digest) {
        payload.clear();
        return false;
    }
    return true;
}
}

std::string superres_sha256_hex(std::string_view value) {
    return superres_sha256_hex(
        reinterpret_cast<const unsigned char*>(value.data()),
        value.size()
    );
}

std::string superres_sha256_hex(const unsigned char* data, std::size_t size) {
    if (data == nullptr && size != 0u) return {};
    return hex(sha256(data, size));
}

bool is_valid_superres_pipeline_cache_key(const std::string& key) {
    return key.size() == 64u && std::all_of(key.begin(), key.end(), [](char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
    });
}

SuperResPipelineCacheStore::SuperResPipelineCacheStore(
    std::filesystem::path root,
    std::size_t maximumEntryBytes,
    std::size_t maximumTotalBytes
) : maximumEntryBytes_(maximumEntryBytes), maximumTotalBytes_(maximumTotalBytes) {
    if (root.empty() || !root.is_absolute() || maximumEntryBytes == 0u ||
        maximumTotalBytes < maximumEntryBytes) {
        return;
    }
    std::error_code error;
    std::filesystem::create_directories(root, error);
    if (error) return;
    root_ = std::filesystem::weakly_canonical(root, error);
    configured_ = !error && root_.is_absolute() && std::filesystem::is_directory(root_, error) && !error;
}

bool SuperResPipelineCacheStore::configured() const {
    return configured_;
}

std::filesystem::path SuperResPipelineCacheStore::cache_path(const std::string& key) const {
    if (!configured_ || !is_valid_superres_pipeline_cache_key(key)) return {};
    return root_ / (key + ".cache");
}

std::vector<unsigned char> SuperResPipelineCacheStore::load(const std::string& key) const {
    const std::filesystem::path path = cache_path(key);
    if (path.empty()) return {};
    std::vector<unsigned char> payload;
    if (!read_envelope(path, maximumEntryBytes_, payload)) {
        std::error_code error;
        std::filesystem::remove(path, error);
        return {};
    }
    return payload;
}

bool SuperResPipelineCacheStore::save(
    const std::string& key,
    const std::vector<unsigned char>& data
) {
    const std::filesystem::path path = cache_path(key);
    if (path.empty() || data.empty() || data.size() > maximumEntryBytes_ ||
        data.size() > maximumTotalBytes_) {
        return false;
    }
    const std::filesystem::path temporary = path.string() + ".part";
    {
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        if (!output) return false;
        output << kEnvelopeMagic << '\n' << data.size() << '\n'
               << superres_sha256_hex(data.data(), data.size()) << '\n';
        output.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
        output.flush();
        if (!output.good()) {
            output.close();
            std::remove(temporary.string().c_str());
            return false;
        }
    }
    if (std::rename(temporary.string().c_str(), path.string().c_str()) != 0) {
        std::remove(temporary.string().c_str());
        return false;
    }
    maintain(key);
    return true;
}

void SuperResPipelineCacheStore::remove(const std::string& key) const {
    const std::filesystem::path path = cache_path(key);
    if (path.empty()) return;
    std::error_code error;
    std::filesystem::remove(path, error);
    std::filesystem::remove(path.string() + ".part", error);
}

void SuperResPipelineCacheStore::maintain(const std::string& protectedKey) {
    struct Entry {
        std::filesystem::path path;
        std::filesystem::file_time_type modified;
        std::size_t payloadBytes;
        bool protectedEntry;
    };
    std::vector<Entry> entries;
    std::size_t totalBytes = 0u;
    std::error_code error;
    for (const auto& item : std::filesystem::directory_iterator(root_, error)) {
        if (error || !item.is_regular_file(error) || error || item.path().extension() != ".cache") {
            error.clear();
            continue;
        }
        const std::string key = item.path().stem().string();
        std::vector<unsigned char> payload;
        if (!is_valid_superres_pipeline_cache_key(key) ||
            !read_envelope(item.path(), maximumEntryBytes_, payload)) {
            std::filesystem::remove(item.path(), error);
            error.clear();
            continue;
        }
        const auto modified = item.last_write_time(error);
        if (error) {
            error.clear();
            continue;
        }
        totalBytes += payload.size();
        entries.push_back(Entry{item.path(), modified, payload.size(), key == protectedKey});
    }
    std::sort(entries.begin(), entries.end(), [](const Entry& left, const Entry& right) {
        if (left.protectedEntry != right.protectedEntry) return !left.protectedEntry;
        if (left.modified != right.modified) return left.modified < right.modified;
        return left.path.string() < right.path.string();
    });
    for (const Entry& entry : entries) {
        if (totalBytes <= maximumTotalBytes_) break;
        if (entry.protectedEntry) continue;
        if (std::filesystem::remove(entry.path, error) && !error) {
            totalBytes -= entry.payloadBytes;
        }
        error.clear();
    }
}
