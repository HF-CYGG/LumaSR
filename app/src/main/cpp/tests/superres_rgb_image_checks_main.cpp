#include "superres_rgb_image_checks.h"
#include "superres_host_checks.h"

int main() {
    return run_superres_rgb_image_runtime_checks() &&
            run_superres_host_runtime_checks()
        ? 0
        : 1;
}
