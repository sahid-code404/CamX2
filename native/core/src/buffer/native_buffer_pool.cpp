#include "camx/native_buffer_pool.hpp"

#include <stdexcept>
#include <utility>

namespace camx {
namespace {

constexpr std::size_t kMaximumBufferSize = 64U * 1024U * 1024U;
constexpr std::size_t kMaximumPoolBytes = 256U * 1024U * 1024U;
constexpr std::size_t kMaximumCapacity = 64U;

}  // namespace

NativeBufferPool::Lease::Lease(
    std::shared_ptr<State> state,
    std::vector<std::byte> buffer) noexcept
    : state_(std::move(state)), buffer_(std::move(buffer)) {}

NativeBufferPool::Lease::~Lease() { release(); }

NativeBufferPool::Lease::Lease(Lease&& other) noexcept
    : state_(std::move(other.state_)), buffer_(std::move(other.buffer_)) {}

NativeBufferPool::Lease& NativeBufferPool::Lease::operator=(Lease&& other) noexcept {
  if (this != &other) {
    release();
    state_ = std::move(other.state_);
    buffer_ = std::move(other.buffer_);
  }
  return *this;
}

void NativeBufferPool::Lease::release() noexcept {
  if (state_ == nullptr || buffer_.empty()) {
    return;
  }
  auto state = std::move(state_);
  const std::lock_guard<std::mutex> lock(state->mutex);
  state->available.push_back(std::move(buffer_));
}

NativeBufferPool::NativeBufferPool(std::size_t buffer_size, std::size_t capacity)
    : state_(std::make_shared<State>()) {
  if (buffer_size == 0U || buffer_size > kMaximumBufferSize || capacity == 0U ||
      capacity > kMaximumCapacity || buffer_size > kMaximumPoolBytes / capacity) {
    throw std::invalid_argument("native buffer pool dimensions are outside bounds");
  }
  state_->buffer_size = buffer_size;
  state_->capacity = capacity;
  state_->available.reserve(capacity);
}

std::optional<NativeBufferPool::Lease> NativeBufferPool::acquire() {
  std::vector<std::byte> buffer;
  {
    const std::lock_guard<std::mutex> lock(state_->mutex);
    if (!state_->available.empty()) {
      buffer = std::move(state_->available.back());
      state_->available.pop_back();
    } else if (state_->allocated < state_->capacity) {
      buffer.resize(state_->buffer_size);
      ++state_->allocated;
    } else {
      return std::nullopt;
    }
  }
  return std::optional<Lease>(Lease(state_, std::move(buffer)));
}

NativeBufferPool::Snapshot NativeBufferPool::snapshot() const {
  const std::lock_guard<std::mutex> lock(state_->mutex);
  return Snapshot{
      .buffer_size = state_->buffer_size,
      .capacity = state_->capacity,
      .allocated = state_->allocated,
      .leased = state_->allocated - state_->available.size(),
  };
}

}  // namespace camx
