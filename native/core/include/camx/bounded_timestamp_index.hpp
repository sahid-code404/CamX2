#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <stdexcept>
#include <utility>

namespace camx {

template <typename Value>
class BoundedTimestampIndex final {
 public:
  explicit BoundedTimestampIndex(std::size_t capacity) : capacity_(capacity) {
    if (capacity_ == 0U || capacity_ > kMaximumCapacity) {
      throw std::invalid_argument("timestamp index capacity is outside bounds");
    }
  }

  BoundedTimestampIndex(const BoundedTimestampIndex&) = delete;
  BoundedTimestampIndex& operator=(const BoundedTimestampIndex&) = delete;
  BoundedTimestampIndex(BoundedTimestampIndex&&) noexcept = default;
  BoundedTimestampIndex& operator=(BoundedTimestampIndex&&) noexcept = default;

  std::optional<Value> insert(std::int64_t timestamp_ns, Value value) {
    if (timestamp_ns <= 0) {
      return std::optional<Value>(std::move(value));
    }
    std::optional<Value> discarded;
    auto existing = entries_.find(timestamp_ns);
    if (existing != entries_.end()) {
      discarded.emplace(std::move(existing->second));
      existing->second = std::move(value);
    } else {
      entries_.emplace(timestamp_ns, std::move(value));
    }
    if (entries_.size() > capacity_) {
      auto oldest = entries_.begin();
      if (!discarded.has_value()) {
        discarded.emplace(std::move(oldest->second));
      }
      entries_.erase(oldest);
    }
    return discarded;
  }

  std::optional<Value> take(std::int64_t timestamp_ns) {
    auto found = entries_.find(timestamp_ns);
    if (found == entries_.end()) {
      return std::nullopt;
    }
    std::optional<Value> value(std::move(found->second));
    entries_.erase(found);
    return value;
  }

  [[nodiscard]] std::size_t size() const noexcept { return entries_.size(); }
  [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }
  void clear() noexcept { entries_.clear(); }

 private:
  static constexpr std::size_t kMaximumCapacity = 64U;
  std::size_t capacity_;
  std::map<std::int64_t, Value> entries_;
};

}  // namespace camx
