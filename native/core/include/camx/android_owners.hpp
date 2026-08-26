#pragma once

#include <utility>

namespace camx {

/**
 * API-neutral move-only owner used by optional public-NDK capability modules.
 *
 * The API-23 baseline deliberately does not include or bind Camera NDK,
 * AImageReader, AImage, or AHardwareBuffer symbols. Optional runtime modules
 * may instantiate these owners only after an explicit capability probe.
 */
template <typename Handle, auto Release>
class UniqueNdkOwner final {
 public:
  UniqueNdkOwner() noexcept = default;
  explicit UniqueNdkOwner(Handle* handle) noexcept : handle_(handle) {}
  ~UniqueNdkOwner() { reset(); }

  UniqueNdkOwner(const UniqueNdkOwner&) = delete;
  UniqueNdkOwner& operator=(const UniqueNdkOwner&) = delete;

  UniqueNdkOwner(UniqueNdkOwner&& other) noexcept
      : handle_(std::exchange(other.handle_, nullptr)) {}

  UniqueNdkOwner& operator=(UniqueNdkOwner&& other) noexcept {
    if (this != &other) {
      reset(std::exchange(other.handle_, nullptr));
    }
    return *this;
  }

  [[nodiscard]] Handle* get() const noexcept { return handle_; }
  [[nodiscard]] explicit operator bool() const noexcept { return handle_ != nullptr; }

  [[nodiscard]] Handle* release() noexcept { return std::exchange(handle_, nullptr); }

  void reset(Handle* replacement = nullptr) noexcept {
    if (handle_ != nullptr) {
      Release(handle_);
    }
    handle_ = replacement;
  }

 private:
  Handle* handle_ = nullptr;
};

/**
 * Move-only owner for dlsym-resolved NDK releases. Keeping the release function
 * beside the owned pointer prevents API-24 symbols from becoming API-23 strong
 * imports while preserving deterministic cleanup on every exit path.
 */
template <typename Handle>
class RuntimeNdkOwner final {
 public:
  using Release = void (*)(Handle*);

  RuntimeNdkOwner() noexcept = default;
  RuntimeNdkOwner(Handle* value, Release release) noexcept : value_(value), release_(release) {}
  ~RuntimeNdkOwner() { reset(); }

  RuntimeNdkOwner(const RuntimeNdkOwner&) = delete;
  RuntimeNdkOwner& operator=(const RuntimeNdkOwner&) = delete;

  RuntimeNdkOwner(RuntimeNdkOwner&& other) noexcept
      : value_(std::exchange(other.value_, nullptr)),
        release_(std::exchange(other.release_, nullptr)) {}

  RuntimeNdkOwner& operator=(RuntimeNdkOwner&& other) noexcept {
    if (this != &other) {
      reset();
      value_ = std::exchange(other.value_, nullptr);
      release_ = std::exchange(other.release_, nullptr);
    }
    return *this;
  }

  [[nodiscard]] Handle* get() const noexcept { return value_; }
  [[nodiscard]] explicit operator bool() const noexcept { return value_ != nullptr; }

  [[nodiscard]] Handle* release() noexcept {
    release_ = nullptr;
    return std::exchange(value_, nullptr);
  }

  void reset(Handle* replacement = nullptr, Release replacement_release = nullptr) noexcept {
    if (value_ != nullptr && release_ != nullptr) release_(value_);
    value_ = replacement;
    release_ = replacement_release;
  }

 private:
  Handle* value_ = nullptr;
  Release release_ = nullptr;
};

}  // namespace camx
