#include "camx/resource_counters.hpp"

#include <limits>

namespace camx {

ResourceCounters::ResourceCounters() noexcept {
  for (auto& value : values_) {
    value.store(0, std::memory_order_relaxed);
  }
}

void ResourceCounters::add(NativeResource resource, std::int64_t amount) noexcept {
  auto& value = values_[static_cast<std::size_t>(resource)];
  auto current = value.load(std::memory_order_relaxed);
  while (true) {
    std::int64_t next = 0;
    if (amount >= 0) {
      const auto maximum = std::numeric_limits<std::int64_t>::max();
      next = current > maximum - amount ? maximum : current + amount;
    } else if (amount == std::numeric_limits<std::int64_t>::min() || current < -amount) {
      next = 0;
    } else {
      next = current + amount;
    }
    if (value.compare_exchange_weak(
            current,
            next,
            std::memory_order_relaxed,
            std::memory_order_relaxed)) {
      return;
    }
  }
}

ResourceCounters::Snapshot ResourceCounters::snapshot() const noexcept {
  Snapshot result{};
  for (std::size_t index = 0U; index < values_.size(); ++index) {
    result[index] = values_[index].load(std::memory_order_relaxed);
  }
  return result;
}

ResourceCounters& GlobalResourceCounters() noexcept {
  static ResourceCounters counters;
  return counters;
}

}  // namespace camx
