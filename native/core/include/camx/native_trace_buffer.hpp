#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace camx {

struct NativeTraceEvent final {
  std::int32_t code;
  std::int64_t timestamp_ns;
  std::int64_t generation;
};

class NativeTraceBuffer final {
 public:
  explicit NativeTraceBuffer(std::size_t capacity);
  NativeTraceBuffer(const NativeTraceBuffer&) = delete;
  NativeTraceBuffer& operator=(const NativeTraceBuffer&) = delete;

  void push(NativeTraceEvent event) noexcept;
  [[nodiscard]] std::vector<NativeTraceEvent> snapshot() const;

 private:
  static constexpr std::size_t kMaximumCapacity = 4096U;
  mutable std::mutex mutex_;
  std::vector<NativeTraceEvent> events_;
  std::size_t size_ = 0U;
  std::size_t next_ = 0U;
};

}  // namespace camx
