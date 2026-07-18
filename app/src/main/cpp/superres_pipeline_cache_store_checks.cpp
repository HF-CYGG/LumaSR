#include "superres_pipeline_cache_store_checks.h"

#include "superres_pipeline_cache_store.h"

#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

bool run_superres_pipeline_cache_store_runtime_checks() {
    if (superres_sha256_hex("") !=
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ||
        superres_sha256_hex("abc") !=
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ||
        is_valid_superres_pipeline_cache_key("../escape") ||
        !is_valid_superres_pipeline_cache_key(superres_sha256_hex("model-a"))) {
        return false;
    }

    const std::filesystem::path root =
        std::filesystem::absolute("superres-host-pipeline-cache");
    std::error_code error;
    std::filesystem::remove_all(root, error);
    SuperResPipelineCacheStore store(root, 8u, 16u);
    const std::string firstKey = superres_sha256_hex("model-a");
    const std::string secondKey = superres_sha256_hex("model-b");
    const std::string thirdKey = superres_sha256_hex("model-c");
    const std::vector<unsigned char> first{1, 2, 3, 4};
    const std::vector<unsigned char> second{5, 6, 7, 8, 9, 10, 11, 12};
    const std::vector<unsigned char> third{13, 14, 15, 16, 17, 18, 19, 20};
    if (!store.configured() || !store.save(firstKey, first) || store.load(firstKey) != first ||
        store.save(firstKey, std::vector<unsigned char>(9u, 0xffu)) ||
        std::filesystem::exists(store.cache_path(firstKey).string() + ".part")) {
        std::filesystem::remove_all(root, error);
        return false;
    }

    if (!store.save(secondKey, second) || !store.save(thirdKey, third) ||
        !store.load(firstKey).empty() || store.load(secondKey) != second ||
        store.load(thirdKey) != third) {
        std::filesystem::remove_all(root, error);
        return false;
    }

    const auto corruptPath = store.cache_path(secondKey);
    {
        std::ofstream corrupt(corruptPath, std::ios::binary | std::ios::trunc);
        corrupt << "corrupt";
    }
    const bool passed = store.load(secondKey).empty() && !std::filesystem::exists(corruptPath);
    std::filesystem::remove_all(root, error);
    return passed;
}
