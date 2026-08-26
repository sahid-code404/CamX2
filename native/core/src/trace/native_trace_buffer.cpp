#include "camx/native_trace_buffer.hpp"

#include <algorithm>

namespace camx {

NativeTraceBuffer::NativeTraceBuffer(std::size_t capacity) : events_(capacity) {
  if (capacity == 0U || capacity > kMaximumCapacity) {
    throw std::invalid_argument("native trace capacity is outside bounds");
  }
}

void NativeTraceBuffer::push(NativeTraceEvent event) noexcept {
  const std::lock_guard<std::mutex> lock(mutex_);
  events_[next_] = event;
  next_ = (next_ + 1U) % events_.size();
  size_ = std::min(size_ + 1U, events_.size());
}

std::vector<NativeTraceEvent> NativeTraceBuffer::snapshot() const {
  const std::lock_guard<std::mutex> lock(mutex_);
  std::vector<NativeTraceEvent> result;
  result.reserve(size_);
  const std::size_t start = size_ == events_.size() ? next_ : 0U;
  for (std::size_t offset = 0U; offset < size_; ++offset) {
    result.push_back(events_[(start + offset) % events_.size()]);
  }
  return result;
}

}  // namespace camx
