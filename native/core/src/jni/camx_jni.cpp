#include <jni.h>

#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "camx/ndk_advertised_metadata.hpp"
#include "camx/resource_counters.hpp"

#if !defined(__ANDROID_API__) || __ANDROID_API__ != 23
#error "libcamx_core must be compiled for the Android API-23 baseline"
#endif

namespace {

jbyteArray ToJavaBytes(
    JNIEnv* environment,
    const std::optional<std::vector<std::uint8_t>>& encoded) {
  if (!encoded.has_value() || encoded->size() > camx::kNdkMaxEncodedBytes) return nullptr;
  const auto size = static_cast<jsize>(encoded->size());
  jbyteArray result = environment->NewByteArray(size);
  if (result == nullptr) return nullptr;
  if (size > 0) {
    environment->SetByteArrayRegion(
        result,
        0,
        size,
        reinterpret_cast<const jbyte*>(encoded->data()));
  }
  if (environment->ExceptionCheck() == JNI_TRUE) return nullptr;
  return result;
}

bool AppendUtf8(std::string& output, std::uint32_t code_point) {
  const std::size_t needed = code_point <= 0x7fU   ? 1U
                           : code_point <= 0x7ffU  ? 2U
                           : code_point <= 0xffffU ? 3U
                                                  : 4U;
  if (needed > camx::kNdkMaxIdBytes - output.size()) return false;
  if (needed == 1U) {
    output.push_back(static_cast<char>(code_point));
  } else if (needed == 2U) {
    output.push_back(static_cast<char>(0xc0U | (code_point >> 6U)));
    output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
  } else if (needed == 3U) {
    output.push_back(static_cast<char>(0xe0U | (code_point >> 12U)));
    output.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
    output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
  } else {
    output.push_back(static_cast<char>(0xf0U | (code_point >> 18U)));
    output.push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3fU)));
    output.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
    output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
  }
  return true;
}

bool JavaStringToUtf8(JNIEnv* environment, jstring value, std::string& output) {
  if (value == nullptr) return false;
  const jsize length = environment->GetStringLength(value);
  if (length <= 0) return false;
  const jchar* chars = environment->GetStringChars(value, nullptr);
  if (chars == nullptr) return false;
  output.clear();
  bool valid = true;
  for (jsize index = 0; index < length && valid; ++index) {
    std::uint32_t code_point = chars[index];
    if (code_point >= 0xd800U && code_point <= 0xdbffU) {
      if (++index >= length) {
        valid = false;
        break;
      }
      const std::uint32_t low = chars[index];
      if (low < 0xdc00U || low > 0xdfffU) {
        valid = false;
        break;
      }
      code_point = 0x10000U + ((code_point - 0xd800U) << 10U) + (low - 0xdc00U);
    } else if (code_point >= 0xdc00U && code_point <= 0xdfffU) {
      valid = false;
      break;
    }
    if (code_point == 0U || !AppendUtf8(output, code_point)) valid = false;
  }
  environment->ReleaseStringChars(value, chars);
  return valid && !output.empty() && output.size() <= camx::kNdkMaxIdBytes;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sahidcode404_camx_core_camera_diagnostics_NativeCore_nativeSnapshot(
    JNIEnv* environment,
    jobject /* receiver */,
    jint android_api) {
  constexpr std::int64_t kSchema = 2;
  constexpr std::size_t kCounterCount =
      static_cast<std::size_t>(camx::NativeResource::kCount);
  const auto resources = camx::GlobalResourceCounters().snapshot();
  constexpr std::size_t kHeaderSize = 4U;
  std::array<jlong, kHeaderSize + kCounterCount> values{};
  values[0] = kSchema;
  values[1] = android_api;
  values[2] = __ANDROID_API__;
  values[3] = static_cast<std::int64_t>(sizeof(void*) * 8U);
  for (std::size_t index = 0U; index < resources.size(); ++index) {
    values[kHeaderSize + index] = resources[index];
  }

  jlongArray result = environment->NewLongArray(static_cast<jsize>(values.size()));
  if (result == nullptr) return nullptr;
  environment->SetLongArrayRegion(
      result,
      0,
      static_cast<jsize>(values.size()),
      values.data());
  if (environment->ExceptionCheck() == JNI_TRUE) return nullptr;
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahidcode404_camx_core_camera_discovery_NdkAdvertisedNativeBridge_nativeCollect(
    JNIEnv* environment,
    jobject /* receiver */,
    jint android_api) {
  return ToJavaBytes(environment, camx::CollectAndroidNdkAdvertisedMetadata(android_api));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahidcode404_camx_core_camera_discovery_NdkDeepNativeBridge_nativeCollectCandidates(
    JNIEnv* environment,
    jobject /* receiver */,
    jint android_api,
    jobjectArray candidate_ids) {
  if (candidate_ids == nullptr) return nullptr;
  const jsize count = environment->GetArrayLength(candidate_ids);
  if (count < 0 || static_cast<std::size_t>(count) > camx::kNdkMaxDeepCandidates) return nullptr;

  std::vector<std::string> candidates;
  candidates.reserve(static_cast<std::size_t>(count));
  for (jsize index = 0; index < count; ++index) {
    auto* value = static_cast<jstring>(environment->GetObjectArrayElement(candidate_ids, index));
    if (value == nullptr || environment->ExceptionCheck() == JNI_TRUE) return nullptr;
    std::string decoded;
    const bool valid = JavaStringToUtf8(environment, value, decoded);
    environment->DeleteLocalRef(value);
    if (!valid || environment->ExceptionCheck() == JNI_TRUE) return nullptr;
    candidates.push_back(std::move(decoded));
  }
  return ToJavaBytes(
      environment,
      camx::CollectAndroidNdkCandidateMetadata(android_api, candidates));
}
