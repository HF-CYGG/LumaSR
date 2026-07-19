#include "superres_rgb_output_target.h"

#include <utility>

namespace {

struct StreamingPngWriterState {
    StreamingPngWriter writer;
};

} // namespace

std::unique_ptr<RgbOutputTarget> make_streaming_png_output() {
    try {
        auto state = std::make_shared<StreamingPngWriterState>();
        StreamingPngOutputOps operations{
            [state](const std::string& outputPath, int width, int height) {
                return state->writer.open(outputPath, width, height);
            },
            [state](const unsigned char* rgb, int rowCount, size_t rowStride) {
                return state->writer.write_rgb_rows(rgb, rowCount, rowStride);
            },
            [state]() { return state->writer.finish_temporary(); },
            [state]() { state->writer.abort(); }
        };
        return std::make_unique<StreamingPngOutput>(std::move(operations));
    } catch (...) {
        return nullptr;
    }
}
