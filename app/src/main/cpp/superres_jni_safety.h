#pragma once

#include "superres_engine.h"

#include <new>
#include <utility>

class SuperResJniPendingException final {};

template <typename Environment>
void clear_superres_jni_exception_if_pending(Environment* environment) noexcept {
    if (environment != nullptr && environment->ExceptionCheck()) {
        environment->ExceptionClear();
    }
}

template <typename Environment>
void throw_if_superres_jni_exception_pending(Environment* environment) {
    if (environment == nullptr || !environment->ExceptionCheck()) {
        return;
    }

    auto throwable = environment->ExceptionOccurred();
    environment->ExceptionClear();

    bool outOfMemory = false;
    bool classificationFailed = false;
    auto outOfMemoryClass = environment->FindClass("java/lang/OutOfMemoryError");
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        classificationFailed = true;
    } else if (outOfMemoryClass == nullptr) {
        classificationFailed = true;
    } else if (throwable != nullptr) {
        outOfMemory = environment->IsInstanceOf(throwable, outOfMemoryClass);
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
            outOfMemory = false;
            classificationFailed = true;
        }
    }

    if (outOfMemoryClass != nullptr) {
        environment->DeleteLocalRef(outOfMemoryClass);
    }
    if (throwable != nullptr) {
        environment->DeleteLocalRef(throwable);
    }

    if (outOfMemory || classificationFailed) {
        throw std::bad_alloc{};
    }
    throw SuperResJniPendingException{};
}

template <typename Environment, typename Body>
int run_superres_process_jni_boundary(Environment* environment, Body&& body) noexcept {
    try {
        throw_if_superres_jni_exception_pending(environment);
        const int code = std::forward<Body>(body)();
        throw_if_superres_jni_exception_pending(environment);
        return code;
    } catch (const std::bad_alloc&) {
        clear_superres_jni_exception_if_pending(environment);
        return static_cast<int>(SuperResNativeCode::OutOfMemory);
    } catch (...) {
        try {
            throw_if_superres_jni_exception_pending(environment);
        } catch (const std::bad_alloc&) {
            return static_cast<int>(SuperResNativeCode::OutOfMemory);
        } catch (...) {
            return static_cast<int>(SuperResNativeCode::InvalidParams);
        }
        return static_cast<int>(SuperResNativeCode::InvalidParams);
    }
}

template <typename Environment, typename Body>
int run_superres_bitmap_jni_boundary(Environment* environment, Body&& body) noexcept {
    return run_superres_process_jni_boundary(environment, std::forward<Body>(body));
}

template <typename Environment, typename StringRef> class SuperResScopedUtfChars {
  public:
    SuperResScopedUtfChars(Environment* environment, StringRef value)
        : environment_(environment), value_(value) {
        if (environment_ != nullptr && value_ != nullptr) {
            chars_ = environment_->GetStringUTFChars(value_, nullptr);
            try {
                throw_if_superres_jni_exception_pending(environment_);
            } catch (...) {
                if (chars_ != nullptr) {
                    environment_->ReleaseStringUTFChars(value_, chars_);
                    chars_ = nullptr;
                }
                throw;
            }
        }
    }

    ~SuperResScopedUtfChars() {
        if (environment_ != nullptr && value_ != nullptr && chars_ != nullptr) {
            environment_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    SuperResScopedUtfChars(const SuperResScopedUtfChars&) = delete;
    SuperResScopedUtfChars& operator=(const SuperResScopedUtfChars&) = delete;

    const char* chars() const noexcept { return chars_; }

  private:
    Environment* environment_;
    StringRef value_;
    const char* chars_ = nullptr;
};

template <typename Environment, typename StringRef, typename Factory>
auto superres_to_string_with(Environment* environment, StringRef value, Factory&& factory)
    -> decltype(std::forward<Factory>(factory)(static_cast<const char*>(nullptr))) {
    if (value == nullptr) {
        return std::forward<Factory>(factory)("");
    }
    SuperResScopedUtfChars<Environment, StringRef> chars(environment, value);
    return std::forward<Factory>(factory)(chars.chars() == nullptr ? "" : chars.chars());
}

template <typename Environment, typename BitmapRef, typename Unlock>
class SuperResBitmapUnlockGuard {
  public:
    SuperResBitmapUnlockGuard(Environment* environment, BitmapRef bitmap, Unlock unlock) noexcept
        : environment_(environment), bitmap_(bitmap), unlock_(std::move(unlock)) {}

    ~SuperResBitmapUnlockGuard() { (void)unlock(); }

    SuperResBitmapUnlockGuard(const SuperResBitmapUnlockGuard&) = delete;
    SuperResBitmapUnlockGuard& operator=(const SuperResBitmapUnlockGuard&) = delete;

    int unlock() noexcept {
        if (!locked_) {
            return 0;
        }
        locked_ = false;
        return unlock_(environment_, bitmap_);
    }

  private:
    Environment* environment_;
    BitmapRef bitmap_;
    Unlock unlock_;
    bool locked_ = true;
};
