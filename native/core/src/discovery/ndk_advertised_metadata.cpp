#include "camx/ndk_advertised_metadata.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <tuple>
#include <utility>

namespace camx {
namespace {

constexpr std::array<std::uint8_t, 4> kMagic{'C', 'X', 'N', '1'};
constexpr std::uint16_t kSchema = 1U;
constexpr std::uint8_t kStatusAvailable = 0U;
constexpr std::uint8_t kStatusUnavailable = 1U;

bool ValidId(std::string_view value) noexcept {
  return !value.empty() && value.size() <= kNdkMaxIdBytes &&
         value.find('\0') == std::string_view::npos;
}

bool ValidPositiveFloat(float value) noexcept {
  return std::isfinite(value) && value > 0.0F;
}

bool ValidSize(const NdkSize& value) noexcept {
  return value.width > 0 && value.height > 0;
}

bool OpaqueLess(std::string_view left, std::string_view right) noexcept {
  const auto left_key = StableNdkOpaqueKey(left);
  const auto right_key = StableNdkOpaqueKey(right);
  if (left_key != right_key) return left_key < right_key;
  return left < right;
}

template <typename T, typename Less>
void SortUnique(std::vector<T>& values, Less less) {
  std::sort(values.begin(), values.end(), less);
  values.erase(std::unique(values.begin(), values.end()), values.end());
}

bool NormalizeRecord(NdkAdvertisedRecord& record,
                     NdkAdvertisedFailureKind& failure_kind) {
  if (!ValidId(record.transport_id)) {
    failure_kind = NdkAdvertisedFailureKind::kInvalidCameraId;
    return false;
  }
  if (record.facing < -1 || record.facing > 2) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }
  if (record.focal_lengths_mm.size() > kNdkMaxFocalLengths ||
      record.apertures.size() > kNdkMaxApertures ||
      record.preview_streams.size() > kNdkMaxPreviewStreams ||
      record.fps_ranges.size() > kNdkMaxFpsRanges ||
      record.raw_sizes.size() > kNdkMaxRawSizes) {
    failure_kind = NdkAdvertisedFailureKind::kMetadataBoundExceeded;
    return false;
  }
  if (!std::all_of(record.focal_lengths_mm.begin(), record.focal_lengths_mm.end(),
                   ValidPositiveFloat) ||
      !std::all_of(record.apertures.begin(), record.apertures.end(),
                   ValidPositiveFloat)) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }
  if (record.sensor_width_mm.has_value() != record.sensor_height_mm.has_value()) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }
  if (record.sensor_width_mm.has_value() &&
      (!ValidPositiveFloat(*record.sensor_width_mm) ||
       !ValidPositiveFloat(*record.sensor_height_mm))) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }
  if ((record.active_array.has_value() && !ValidSize(*record.active_array)) ||
      (record.pixel_array.has_value() && !ValidSize(*record.pixel_array))) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }
  if (record.sensor_orientation_degrees.has_value()) {
    const auto orientation = *record.sensor_orientation_degrees;
    if (orientation < 0 || orientation > 270 || orientation % 90 != 0) {
      failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
      return false;
    }
  }
  for (const auto& stream : record.preview_streams) {
    if (!ValidSize(stream.size) ||
        (stream.minimum_frame_duration_ns.has_value() &&
         *stream.minimum_frame_duration_ns < 0)) {
      failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
      return false;
    }
  }
  for (const auto& range : record.fps_ranges) {
    if (range.minimum <= 0 || range.maximum < range.minimum) {
      failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
      return false;
    }
  }
  if (!std::all_of(record.raw_sizes.begin(), record.raw_sizes.end(), ValidSize)) {
    failure_kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    return false;
  }

  SortUnique(record.focal_lengths_mm, std::less<float>());
  SortUnique(record.apertures, std::less<float>());
  SortUnique(record.preview_streams, [](const NdkPreviewStream& left,
                                       const NdkPreviewStream& right) {
    return std::tie(left.size.width, left.size.height, left.minimum_frame_duration_ns) <
           std::tie(right.size.width, right.size.height, right.minimum_frame_duration_ns);
  });
  SortUnique(record.fps_ranges, [](const NdkFpsRange& left, const NdkFpsRange& right) {
    return std::tie(left.minimum, left.maximum) < std::tie(right.minimum, right.maximum);
  });
  SortUnique(record.raw_sizes, [](const NdkSize& left, const NdkSize& right) {
    return std::tie(left.width, left.height) < std::tie(right.width, right.height);
  });
  return true;
}

class BoundedWriter final {
 public:
  bool byte(std::uint8_t value) { return append(&value, sizeof(value)); }

  bool u16(std::uint16_t value) {
    std::array<std::uint8_t, 2> bytes{
        static_cast<std::uint8_t>(value & 0xffU),
        static_cast<std::uint8_t>((value >> 8U) & 0xffU),
    };
    return append(bytes.data(), bytes.size());
  }

  bool u32(std::uint32_t value) {
    std::array<std::uint8_t, 4> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
      bytes[index] = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
    }
    return append(bytes.data(), bytes.size());
  }

  bool i32(std::int32_t value) { return u32(std::bit_cast<std::uint32_t>(value)); }

  bool i64(std::int64_t value) {
    const auto bits = std::bit_cast<std::uint64_t>(value);
    std::array<std::uint8_t, 8> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
      bytes[index] = static_cast<std::uint8_t>((bits >> (index * 8U)) & 0xffU);
    }
    return append(bytes.data(), bytes.size());
  }

  bool f32(float value) { return u32(std::bit_cast<std::uint32_t>(value)); }

  bool string(std::string_view value) {
    if (!ValidId(value) || value.size() > std::numeric_limits<std::uint16_t>::max()) return false;
    return u16(static_cast<std::uint16_t>(value.size())) &&
           append(reinterpret_cast<const std::uint8_t*>(value.data()), value.size());
  }

  bool raw(const std::array<std::uint8_t, 4>& value) {
    return append(value.data(), value.size());
  }

  [[nodiscard]] std::vector<std::uint8_t> take() && { return std::move(bytes_); }

 private:
  bool append(const std::uint8_t* data, std::size_t count) {
    if (count > kNdkMaxEncodedBytes - bytes_.size()) return false;
    bytes_.insert(bytes_.end(), data, data + count);
    return true;
  }

  std::vector<std::uint8_t> bytes_;
};

bool EncodeRecord(BoundedWriter& writer, const NdkAdvertisedRecord& record) {
  std::uint8_t flags = 0U;
  if (record.sensor_width_mm.has_value()) flags |= 1U << 0U;
  if (record.active_array.has_value()) flags |= 1U << 1U;
  if (record.pixel_array.has_value()) flags |= 1U << 2U;
  if (record.sensor_orientation_degrees.has_value()) flags |= 1U << 3U;
  if (record.color_filter_arrangement.has_value()) flags |= 1U << 4U;

  if (!writer.string(record.transport_id) ||
      !writer.byte(static_cast<std::uint8_t>(record.facing + 1)) || !writer.byte(flags) ||
      !writer.u16(static_cast<std::uint16_t>(record.focal_lengths_mm.size())) ||
      !writer.u16(static_cast<std::uint16_t>(record.apertures.size())) ||
      !writer.u16(static_cast<std::uint16_t>(record.preview_streams.size())) ||
      !writer.u16(static_cast<std::uint16_t>(record.fps_ranges.size())) ||
      !writer.u16(static_cast<std::uint16_t>(record.raw_sizes.size()))) {
    return false;
  }
  if ((flags & (1U << 0U)) != 0U &&
      (!writer.f32(*record.sensor_width_mm) || !writer.f32(*record.sensor_height_mm))) {
    return false;
  }
  if ((flags & (1U << 1U)) != 0U &&
      (!writer.i32(record.active_array->width) || !writer.i32(record.active_array->height))) {
    return false;
  }
  if ((flags & (1U << 2U)) != 0U &&
      (!writer.i32(record.pixel_array->width) || !writer.i32(record.pixel_array->height))) {
    return false;
  }
  if ((flags & (1U << 3U)) != 0U && !writer.i32(*record.sensor_orientation_degrees)) {
    return false;
  }
  if ((flags & (1U << 4U)) != 0U && !writer.i32(*record.color_filter_arrangement)) {
    return false;
  }
  for (float value : record.focal_lengths_mm) {
    if (!writer.f32(value)) return false;
  }
  for (float value : record.apertures) {
    if (!writer.f32(value)) return false;
  }
  for (const auto& stream : record.preview_streams) {
    if (!writer.i32(stream.size.width) || !writer.i32(stream.size.height) ||
        !writer.i64(stream.minimum_frame_duration_ns.value_or(-1))) {
      return false;
    }
  }
  for (const auto& range : record.fps_ranges) {
    if (!writer.i32(range.minimum) || !writer.i32(range.maximum)) return false;
  }
  for (const auto& size : record.raw_sizes) {
    if (!writer.i32(size.width) || !writer.i32(size.height)) return false;
  }
  return true;
}

}  // namespace

std::uint64_t StableNdkOpaqueKey(std::string_view value) noexcept {
  constexpr std::uint64_t kOffset = 14695981039346656037ULL;
  constexpr std::uint64_t kPrime = 1099511628211ULL;
  std::uint64_t hash = kOffset;
  constexpr std::string_view kSalt = "camx-ndk-order|";
  for (const unsigned char byte : kSalt) {
    hash ^= byte;
    hash *= kPrime;
  }
  for (const unsigned char byte : value) {
    hash ^= byte;
    hash *= kPrime;
  }
  return hash;
}

NdkAdvertisedReport CollectNdkAdvertisedMetadata(NdkAdvertisedMetadataSource& source) {
  NdkAdvertisedReport report;
  if (!source.runtime_available()) {
    report.runtime_available = false;
    return report;
  }

  std::vector<std::string> ids;
  if (!source.camera_ids(ids)) {
    report.failures.push_back({NdkAdvertisedFailureKind::kIdEnumerationUnavailable, {}});
    return report;
  }
  if (ids.size() > kNdkMaxCameraIds) {
    report.failures.push_back({NdkAdvertisedFailureKind::kCameraIdLimitExceeded, {}});
    return report;
  }

  std::vector<std::string> unique_ids;
  unique_ids.reserve(ids.size());
  for (auto& id : ids) {
    if (!ValidId(id)) {
      if (report.failures.size() < kNdkMaxFailures) {
        report.failures.push_back({NdkAdvertisedFailureKind::kInvalidCameraId, {}});
      }
      continue;
    }
    if (std::find(unique_ids.begin(), unique_ids.end(), id) == unique_ids.end()) {
      unique_ids.push_back(std::move(id));
    }
  }
  std::sort(unique_ids.begin(), unique_ids.end(), OpaqueLess);

  for (const auto& id : unique_ids) {
    NdkAdvertisedRecord record;
    if (!source.read(id, record)) {
      if (report.failures.size() < kNdkMaxFailures) {
        report.failures.push_back({NdkAdvertisedFailureKind::kMetadataUnavailable, id});
      }
      continue;
    }
    if (record.transport_id != id) {
      if (report.failures.size() < kNdkMaxFailures) {
        report.failures.push_back({NdkAdvertisedFailureKind::kMalformedMetadata, id});
      }
      continue;
    }
    NdkAdvertisedFailureKind kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    if (!NormalizeRecord(record, kind)) {
      if (report.failures.size() < kNdkMaxFailures) report.failures.push_back({kind, id});
      continue;
    }
    report.records.push_back(std::move(record));
  }

  std::sort(report.records.begin(), report.records.end(), [](const auto& left, const auto& right) {
    return OpaqueLess(left.transport_id, right.transport_id);
  });
  std::sort(report.failures.begin(), report.failures.end(), [](const auto& left, const auto& right) {
    if (left.kind != right.kind) {
      return static_cast<std::uint8_t>(left.kind) < static_cast<std::uint8_t>(right.kind);
    }
    return OpaqueLess(left.transport_id, right.transport_id);
  });
  return report;
}

std::optional<std::vector<std::uint8_t>> EncodeNdkAdvertisedReport(
    const NdkAdvertisedReport& input) {
  NdkAdvertisedReport report = input;
  if (report.records.size() > kNdkMaxCameraIds || report.failures.size() > kNdkMaxFailures) {
    return std::nullopt;
  }
  for (auto& record : report.records) {
    NdkAdvertisedFailureKind kind = NdkAdvertisedFailureKind::kMalformedMetadata;
    if (!NormalizeRecord(record, kind)) return std::nullopt;
  }
  std::sort(report.records.begin(), report.records.end(), [](const auto& left, const auto& right) {
    return OpaqueLess(left.transport_id, right.transport_id);
  });
  std::sort(report.failures.begin(), report.failures.end(), [](const auto& left, const auto& right) {
    if (left.kind != right.kind) {
      return static_cast<std::uint8_t>(left.kind) < static_cast<std::uint8_t>(right.kind);
    }
    return OpaqueLess(left.transport_id, right.transport_id);
  });

  BoundedWriter writer;
  if (!writer.raw(kMagic) || !writer.u16(kSchema) ||
      !writer.byte(report.runtime_available ? kStatusAvailable : kStatusUnavailable) ||
      !writer.byte(0U) || !writer.u16(static_cast<std::uint16_t>(report.records.size())) ||
      !writer.u16(static_cast<std::uint16_t>(report.failures.size()))) {
    return std::nullopt;
  }
  for (const auto& record : report.records) {
    if (!EncodeRecord(writer, record)) return std::nullopt;
  }
  for (const auto& failure : report.failures) {
    if (!writer.byte(static_cast<std::uint8_t>(failure.kind))) return std::nullopt;
    if (failure.transport_id.empty()) {
      if (!writer.u16(0U)) return std::nullopt;
    } else if (!writer.string(failure.transport_id)) {
      return std::nullopt;
    }
  }
  return std::move(writer).take();
}

}  // namespace camx
