#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace camx {

inline constexpr std::size_t kNdkMaxCameraIds = 64U;
inline constexpr std::size_t kNdkMaxFocalLengths = 16U;
inline constexpr std::size_t kNdkMaxApertures = 16U;
inline constexpr std::size_t kNdkMaxPreviewStreams = 128U;
inline constexpr std::size_t kNdkMaxFpsRanges = 64U;
inline constexpr std::size_t kNdkMaxRawSizes = 64U;
inline constexpr std::size_t kNdkMaxMetadataTags = 512U;
inline constexpr std::size_t kNdkMaxIdBytes = 256U;
inline constexpr std::size_t kNdkMaxEncodedBytes = 1024U * 1024U;
inline constexpr std::size_t kNdkMaxFailures = 128U;

struct NdkSize final {
  std::int32_t width = 0;
  std::int32_t height = 0;
  bool operator==(const NdkSize&) const = default;
};

struct NdkPreviewStream final {
  NdkSize size{};
  std::optional<std::int64_t> minimum_frame_duration_ns;
  bool operator==(const NdkPreviewStream&) const = default;
};

struct NdkFpsRange final {
  std::int32_t minimum = 0;
  std::int32_t maximum = 0;
  bool operator==(const NdkFpsRange&) const = default;
};

/**
 * API-neutral normalized metadata record. Android Camera-NDK parsing happens in
 * the runtime adapter; collection/validation/encoding stay host-testable.
 */
struct NdkAdvertisedRecord final {
  std::string transport_id;
  std::int8_t facing = -1;  // -1 unknown, 0 front, 1 back, 2 external.
  std::vector<float> focal_lengths_mm;
  std::optional<float> sensor_width_mm;
  std::optional<float> sensor_height_mm;
  std::optional<NdkSize> active_array;
  std::optional<NdkSize> pixel_array;
  std::optional<std::int32_t> sensor_orientation_degrees;
  std::vector<float> apertures;
  std::optional<std::int32_t> color_filter_arrangement;
  std::vector<NdkPreviewStream> preview_streams;
  std::vector<NdkFpsRange> fps_ranges;
  std::vector<NdkSize> raw_sizes;
  bool operator==(const NdkAdvertisedRecord&) const = default;
};

enum class NdkAdvertisedFailureKind : std::uint8_t {
  kIdEnumerationUnavailable = 1,
  kCameraIdLimitExceeded = 2,
  kInvalidCameraId = 3,
  kMetadataUnavailable = 4,
  kMalformedMetadata = 5,
  kMetadataBoundExceeded = 6,
};

struct NdkAdvertisedFailure final {
  NdkAdvertisedFailureKind kind = NdkAdvertisedFailureKind::kMetadataUnavailable;
  std::string transport_id;
  bool operator==(const NdkAdvertisedFailure&) const = default;
};

struct NdkAdvertisedReport final {
  bool runtime_available = true;
  std::vector<NdkAdvertisedRecord> records;
  std::vector<NdkAdvertisedFailure> failures;
  bool operator==(const NdkAdvertisedReport&) const = default;
};

class NdkAdvertisedMetadataSource {
 public:
  virtual ~NdkAdvertisedMetadataSource() = default;

  [[nodiscard]] virtual bool runtime_available() const noexcept = 0;
  virtual bool camera_ids(std::vector<std::string>& output) noexcept = 0;
  virtual bool read(std::string_view transport_id, NdkAdvertisedRecord& output) noexcept = 0;
};

[[nodiscard]] std::uint64_t StableNdkOpaqueKey(std::string_view value) noexcept;
[[nodiscard]] NdkAdvertisedReport CollectNdkAdvertisedMetadata(
    NdkAdvertisedMetadataSource& source);
[[nodiscard]] std::optional<std::vector<std::uint8_t>> EncodeNdkAdvertisedReport(
    const NdkAdvertisedReport& report);

/** Android-only adapter implemented without strong Camera-NDK imports. */
[[nodiscard]] std::optional<std::vector<std::uint8_t>>
CollectAndroidNdkAdvertisedMetadata(std::int32_t android_api);

}  // namespace camx
