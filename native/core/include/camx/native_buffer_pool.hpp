#pragma once

#include <cstddef>
#include <memory>
#include <mutex>
#include <optional>
#include <vector>

namespace camx {

class NativeBufferPool final {
 private:
  struct State;

 public:
  struct Snapshot final {
    std::size_t buffer_size;
    std::size_t capacity;
    std::size_t allocated;
    std::size_t leased;
  };

  class Lease final {
   public:
    ~Lease();
    Lease(const Lease&) = delete;
    Lease& operator=(const Lease&) = delete;
    Lease(Lease&& other) noexcept;
    Lease& operator=(Lease&& other) noexcept;

    [[nodiscard]] std::byte* data() noexcept { return buffer_.data(); }
    [[nodiscard]] const std::byte* data() const noexcept { return buffer_.data(); }
    [[nodiscard]] std::size_t size() const noexcept { return buffer_.size(); }

   private:
    friend class NativeBufferPool;
    Lease(std::shared_ptr<State> state, std::vector<std::byte> buffer) noexcept;
    void release() noexcept;

    std::shared_ptr<State> state_;
    std::vector<std::byte> buffer_;
  };

  NativeBufferPool(std::size_t buffer_size, std::size_t capacity);
  NativeBufferPool(const NativeBufferPool&) = delete;
  NativeBufferPool& operator=(const NativeBufferPool&) = delete;

  [[nodiscard]] std::optional<Lease> acquire();
  [[nodiscard]] Snapshot snapshot() const;

 private:
  struct State final {
    std::mutex mutex;
    std::size_t buffer_size;
    std::size_t capacity;
    std::size_t allocated = 0U;
    std::vector<std::vector<std::byte>> available;
  };

  std::shared_ptr<State> state_;
};

}  // namespace camx
