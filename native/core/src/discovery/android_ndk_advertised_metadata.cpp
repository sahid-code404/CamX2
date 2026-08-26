#include "camx/ndk_advertised_metadata.hpp"

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <dlfcn.h>
#include <media/NdkImage.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "camx/android_owners.hpp"

#if !defined(__ANDROID_API__) || __ANDROID_API__ != 23
#error "Camera-NDK runtime adapter must remain inside the Android API-23 baseline library"
#endif

namespace camx {
namespace {

constexpr std::int32_t kCameraNdkMinimumApi = 24;
constexpr std::size_t kMaxCapabilityValues = 64U;
constexpr std::size_t kMaxStreamConfigurationTuples = 512U;
constexpr std::size_t kMaxMinFrameDurationTuples = 512U;

class DynamicLibrary final {
 public:
  explicit DynamicLibrary(const char* name) noexcept
      : library_(dlopen(name, RTLD_NOW | RTLD_LOCAL)) {}
  ~DynamicLibrary() {
    if (library_ != nullptr) dlclose(library_);
  }

  DynamicLibrary(const DynamicLibrary&) = delete;
  DynamicLibrary& operator=(const DynamicLibrary&) = delete;

  [[nodiscard]] bool available() const noexcept { return library_ != nullptr; }

  template <typename Function>
  [[nodiscard]] Function symbol(const char* name) const noexcept {
    if (library_ == nullptr) return nullptr;
    return reinterpret_cast<Function>(dlsym(library_, name));
  }

 private:
  void* library_ = nullptr;
};

struct CameraNdkFunctions final {
  // Deliberately spell the ABI signatures rather than using decltype on the
  // API-24 declarations. This translation unit is compiled at android-23 and
  // must never take the address of an unavailable Camera-NDK symbol.
  using CreateManager = ACameraManager* (*)();
  using DeleteManager = void (*)(ACameraManager*);
  using GetCameraIdList = camera_status_t (*)(ACameraManager*, ACameraIdList**);
  using DeleteCameraIdList = void (*)(ACameraIdList*);
  using GetCameraCharacteristics =
      camera_status_t (*)(ACameraManager*, const char*, ACameraMetadata**);
  using FreeMetadata = void (*)(ACameraMetadata*);
  using GetAllTags =
      camera_status_t (*)(const ACameraMetadata*, std::int32_t*, const std::uint32_t**);
  using GetConstEntry =
      camera_status_t (*)(const ACameraMetadata*, std::uint32_t, ACameraMetadata_const_entry*);

  CreateManager create_manager = nullptr;
  DeleteManager delete_manager = nullptr;
  GetCameraIdList get_camera_id_list = nullptr;
  DeleteCameraIdList delete_camera_id_list = nullptr;
  GetCameraCharacteristics get_camera_characteristics = nullptr;
  FreeMetadata free_metadata = nullptr;
  GetAllTags get_all_tags = nullptr;
  GetConstEntry get_const_entry = nullptr;

  [[nodiscard]] bool complete() const noexcept {
    return create_manager != nullptr && delete_manager != nullptr &&
           get_camera_id_list != nullptr && delete_camera_id_list != nullptr &&
           get_camera_characteristics != nullptr && free_metadata != nullptr &&
           get_all_tags != nullptr && get_const_entry != nullptr;
  }
};

CameraNdkFunctions LoadFunctions(const DynamicLibrary& library) noexcept {
  CameraNdkFunctions functions;
  functions.create_manager =
      library.symbol<CameraNdkFunctions::CreateManager>("ACameraManager_create");
  functions.delete_manager =
      library.symbol<CameraNdkFunctions::DeleteManager>("ACameraManager_delete");
  functions.get_camera_id_list =
      library.symbol<CameraNdkFunctions::GetCameraIdList>("ACameraManager_getCameraIdList");
  functions.delete_camera_id_list = library.symbol<CameraNdkFunctions::DeleteCameraIdList>(
      "ACameraManager_deleteCameraIdList");
  functions.get_camera_characteristics =
      library.symbol<CameraNdkFunctions::GetCameraCharacteristics>(
          "ACameraManager_getCameraCharacteristics");
  functions.free_metadata =
      library.symbol<CameraNdkFunctions::FreeMetadata>("ACameraMetadata_free");
  functions.get_all_tags =
      library.symbol<CameraNdkFunctions::GetAllTags>("ACameraMetadata_getAllTags");
  functions.get_const_entry =
      library.symbol<CameraNdkFunctions::GetConstEntry>("ACameraMetadata_getConstEntry");
  return functions;
}

enum class EntryResult { kMissing, kPresent, kError };

class AndroidCameraNdkSource final : public NdkAdvertisedMetadataSource {
 public:
  AndroidCameraNdkSource(std::int32_t android_api, const CameraNdkFunctions& functions) noexcept
      : android_api_(android_api), functions_(functions) {
    if (android_api_ >= kCameraNdkMinimumApi && functions_.complete()) {
      manager_.reset(functions_.create_manager(), functions_.delete_manager);
    }
  }

  [[nodiscard]] bool runtime_available() const noexcept override {
    return android_api_ >= kCameraNdkMinimumApi && functions_.complete() && manager_;
  }

  bool camera_ids(std::vector<std::string>& output) noexcept override {
    output.clear();
    if (!runtime_available()) return false;
    ACameraIdList* raw_list = nullptr;
    if (functions_.get_camera_id_list(manager_.get(), &raw_list) != ACAMERA_OK ||
        raw_list == nullptr) {
      return false;
    }
    RuntimeNdkOwner<ACameraIdList> list(raw_list, functions_.delete_camera_id_list);
    if (raw_list->numCameras < 0 ||
        static_cast<std::size_t>(raw_list->numCameras) > kNdkMaxCameraIds) {
      return false;
    }
    if (raw_list->numCameras > 0 && raw_list->cameraIds == nullptr) return false;

    output.reserve(static_cast<std::size_t>(raw_list->numCameras));
    for (std::int32_t index = 0; index < raw_list->numCameras; ++index) {
      const char* value = raw_list->cameraIds[index];
      if (value == nullptr) return false;
      const auto length = strnlen(value, kNdkMaxIdBytes + 1U);
      if (length == 0U || length > kNdkMaxIdBytes) return false;
      output.emplace_back(value, length);
    }
    return true;
  }

  bool read(std::string_view transport_id, NdkAdvertisedRecord& output) noexcept override {
    if (!runtime_available() || transport_id.empty() || transport_id.size() > kNdkMaxIdBytes) {
      return false;
    }
    const std::string id(transport_id);
    ACameraMetadata* raw_metadata = nullptr;
    if (functions_.get_camera_characteristics(manager_.get(), id.c_str(), &raw_metadata) !=
            ACAMERA_OK ||
        raw_metadata == nullptr) {
      return false;
    }
    RuntimeNdkOwner<ACameraMetadata> metadata(raw_metadata, functions_.free_metadata);
    if (!validate_tag_bound(raw_metadata)) return false;

    NdkAdvertisedRecord record;
    record.transport_id = id;
    if (!read_facing(raw_metadata, record.facing) ||
        !read_positive_floats(raw_metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                              kNdkMaxFocalLengths, record.focal_lengths_mm) ||
        !read_sensor_size(raw_metadata, record.sensor_width_mm, record.sensor_height_mm) ||
        !read_active_array(raw_metadata, record.active_array) ||
        !read_pixel_array(raw_metadata, record.pixel_array) ||
        !read_orientation(raw_metadata, record.sensor_orientation_degrees) ||
        !read_positive_floats(raw_metadata, ACAMERA_LENS_INFO_AVAILABLE_APERTURES,
                              kNdkMaxApertures, record.apertures) ||
        !read_cfa(raw_metadata, record.color_filter_arrangement)) {
      return false;
    }

    bool raw_advertised = false;
    if (!read_raw_capability(raw_metadata, raw_advertised)) return false;
    if (!read_streams(raw_metadata, raw_advertised, record.preview_streams, record.raw_sizes) ||
        !read_min_frame_durations(raw_metadata, record.preview_streams) ||
        !read_fps_ranges(raw_metadata, record.fps_ranges)) {
      return false;
    }
    output = std::move(record);
    return true;
  }

 private:
  EntryResult entry(const ACameraMetadata* metadata, std::uint32_t tag,
                    ACameraMetadata_const_entry& output) const noexcept {
    const auto status = functions_.get_const_entry(metadata, tag, &output);
    if (status == ACAMERA_OK) return EntryResult::kPresent;
    if (status == ACAMERA_ERROR_METADATA_NOT_FOUND) return EntryResult::kMissing;
    return EntryResult::kError;
  }

  bool validate_tag_bound(const ACameraMetadata* metadata) const noexcept {
    std::int32_t count = 0;
    const std::uint32_t* tags = nullptr;
    if (functions_.get_all_tags(metadata, &count, &tags) != ACAMERA_OK) return false;
    if (count < 0 || static_cast<std::size_t>(count) > kNdkMaxMetadataTags) return false;
    return count == 0 || tags != nullptr;
  }

  bool read_facing(const ACameraMetadata* metadata, std::int8_t& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_LENS_FACING, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_BYTE || value.count != 1U ||
        value.data.u8 == nullptr) {
      return false;
    }
    if (value.data.u8[0] == ACAMERA_LENS_FACING_FRONT) {
      output = 0;
    } else if (value.data.u8[0] == ACAMERA_LENS_FACING_BACK) {
      output = 1;
    } else {
      output = -1;
    }
    return true;
  }

  bool read_positive_floats(const ACameraMetadata* metadata, std::uint32_t tag,
                            std::size_t maximum, std::vector<float>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, tag, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_FLOAT ||
        value.count > maximum || (value.count > 0U && value.data.f == nullptr)) {
      return false;
    }
    output.reserve(value.count);
    for (std::uint32_t index = 0; index < value.count; ++index) {
      const float item = value.data.f[index];
      if (!std::isfinite(item) || item <= 0.0F) return false;
      output.push_back(item);
    }
    return true;
  }

  bool read_sensor_size(const ACameraMetadata* metadata, std::optional<float>& width,
                        std::optional<float>& height) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SENSOR_INFO_PHYSICAL_SIZE, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_FLOAT || value.count != 2U ||
        value.data.f == nullptr || !std::isfinite(value.data.f[0]) ||
        !std::isfinite(value.data.f[1]) || value.data.f[0] <= 0.0F || value.data.f[1] <= 0.0F) {
      return false;
    }
    width = value.data.f[0];
    height = value.data.f[1];
    return true;
  }

  bool read_active_array(const ACameraMetadata* metadata,
                         std::optional<NdkSize>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT32 || value.count != 4U ||
        value.data.i32 == nullptr) {
      return false;
    }
    const auto width = value.data.i32[2] - value.data.i32[0];
    const auto height = value.data.i32[3] - value.data.i32[1];
    if (width <= 0 || height <= 0) return false;
    output = NdkSize{width, height};
    return true;
  }

  bool read_pixel_array(const ACameraMetadata* metadata,
                        std::optional<NdkSize>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SENSOR_INFO_PIXEL_ARRAY_SIZE, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT32 || value.count != 2U ||
        value.data.i32 == nullptr || value.data.i32[0] <= 0 || value.data.i32[1] <= 0) {
      return false;
    }
    output = NdkSize{value.data.i32[0], value.data.i32[1]};
    return true;
  }

  bool read_orientation(const ACameraMetadata* metadata,
                        std::optional<std::int32_t>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SENSOR_ORIENTATION, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT32 || value.count != 1U ||
        value.data.i32 == nullptr) {
      return false;
    }
    const auto orientation = value.data.i32[0];
    if (orientation < 0 || orientation > 270 || orientation % 90 != 0) return false;
    output = orientation;
    return true;
  }

  bool read_cfa(const ACameraMetadata* metadata,
                std::optional<std::int32_t>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_BYTE || value.count != 1U ||
        value.data.u8 == nullptr) {
      return false;
    }
    output = static_cast<std::int32_t>(value.data.u8[0]);
    return true;
  }

  bool read_raw_capability(const ACameraMetadata* metadata, bool& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_BYTE ||
        value.count > kMaxCapabilityValues || (value.count > 0U && value.data.u8 == nullptr)) {
      return false;
    }
    for (std::uint32_t index = 0; index < value.count; ++index) {
      if (value.data.u8[index] == ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW) output = true;
    }
    return true;
  }

  bool read_streams(const ACameraMetadata* metadata, bool raw_advertised,
                    std::vector<NdkPreviewStream>& preview,
                    std::vector<NdkSize>& raw) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT32 ||
        value.count % 4U != 0U ||
        value.count / 4U > kMaxStreamConfigurationTuples ||
        (value.count > 0U && value.data.i32 == nullptr)) {
      return false;
    }
    for (std::uint32_t offset = 0; offset < value.count; offset += 4U) {
      const auto format = value.data.i32[offset];
      const auto width = value.data.i32[offset + 1U];
      const auto height = value.data.i32[offset + 2U];
      const auto input = value.data.i32[offset + 3U];
      if (input != 0 || width <= 0 || height <= 0) continue;
      if (format == AIMAGE_FORMAT_PRIVATE) {
        if (preview.size() >= kNdkMaxPreviewStreams) return false;
        preview.push_back({NdkSize{width, height}, std::nullopt});
      } else if (raw_advertised && format == AIMAGE_FORMAT_RAW16) {
        if (raw.size() >= kNdkMaxRawSizes) return false;
        raw.push_back({width, height});
      }
    }
    return true;
  }

  bool read_min_frame_durations(const ACameraMetadata* metadata,
                                std::vector<NdkPreviewStream>& preview) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT64 ||
        value.count % 4U != 0U || value.count / 4U > kMaxMinFrameDurationTuples ||
        (value.count > 0U && value.data.i64 == nullptr)) {
      return false;
    }
    for (std::uint32_t offset = 0; offset < value.count; offset += 4U) {
      const auto format = value.data.i64[offset];
      const auto width = value.data.i64[offset + 1U];
      const auto height = value.data.i64[offset + 2U];
      const auto duration = value.data.i64[offset + 3U];
      if (format != AIMAGE_FORMAT_PRIVATE || width <= 0 || height <= 0 || duration < 0) continue;
      for (auto& stream : preview) {
        if (stream.size.width == width && stream.size.height == height) {
          stream.minimum_frame_duration_ns = duration;
        }
      }
    }
    return true;
  }

  bool read_fps_ranges(const ACameraMetadata* metadata,
                       std::vector<NdkFpsRange>& output) const noexcept {
    ACameraMetadata_const_entry value{};
    const auto result = entry(metadata, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, value);
    if (result == EntryResult::kMissing) return true;
    if (result == EntryResult::kError || value.type != ACAMERA_TYPE_INT32 ||
        value.count % 2U != 0U || value.count / 2U > kNdkMaxFpsRanges ||
        (value.count > 0U && value.data.i32 == nullptr)) {
      return false;
    }
    output.reserve(value.count / 2U);
    for (std::uint32_t offset = 0; offset < value.count; offset += 2U) {
      const auto minimum = value.data.i32[offset];
      const auto maximum = value.data.i32[offset + 1U];
      if (minimum <= 0 || maximum < minimum) return false;
      output.push_back({minimum, maximum});
    }
    return true;
  }

  std::int32_t android_api_;
  CameraNdkFunctions functions_;
  RuntimeNdkOwner<ACameraManager> manager_;
};

}  // namespace

std::optional<std::vector<std::uint8_t>> CollectAndroidNdkAdvertisedMetadata(
    std::int32_t android_api) {
  if (android_api < kCameraNdkMinimumApi) {
    NdkAdvertisedReport unavailable;
    unavailable.runtime_available = false;
    return EncodeNdkAdvertisedReport(unavailable);
  }

  DynamicLibrary library("libcamera2ndk.so");
  if (!library.available()) {
    NdkAdvertisedReport unavailable;
    unavailable.runtime_available = false;
    return EncodeNdkAdvertisedReport(unavailable);
  }
  const auto functions = LoadFunctions(library);
  if (!functions.complete()) {
    NdkAdvertisedReport unavailable;
    unavailable.runtime_available = false;
    return EncodeNdkAdvertisedReport(unavailable);
  }

  AndroidCameraNdkSource source(android_api, functions);
  return EncodeNdkAdvertisedReport(CollectNdkAdvertisedMetadata(source));
}

}  // namespace camx
