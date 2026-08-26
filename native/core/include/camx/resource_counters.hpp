#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace camx {

enum class NativeResource : std::size_t {
  kImages = 0,
  kHardwareBuffers,
  kBufferBytes,
  kWorkers,
  kQueueDepth,
  kJniGlobalReferences,
  kCount,
};

class ResourceCounters final {
 public:
  using Snapshot = std::array<std::int64_t, static_cast<std::size_t>(NativeResource::kCount)>;

  ResourceCounters() noexcept;
  ResourceCounters(const ResourceCounters&) = delete;
  ResourceCounters& operator=(const ResourceCounters&) = delete;

  void add(NativeResource resource, std::int64_t amount) noexcept;
  [[nodiscard]] Snapshot snapshot() const noexcept;

 private:
  std::array<std::atomic<std::int64_t>, static_cast<std::size_t>(NativeResource::kCount)> values_;
};

ResourceCounters& GlobalResourceCounters() noexcept;

}  // namespace camx
