#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include "camx/ndk_advertised_metadata.hpp"
#include "camx/reconstruction_simd.hpp"
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

bool ExactFloatArrayLength(JNIEnv* environment, jfloatArray value, std::size_t expected) {
  if (value == nullptr || expected > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
    return false;
  }
  return environment->GetArrayLength(value) == static_cast<jsize>(expected);
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

extern "C" JNIEXPORT jint JNICALL
Java_com_sahidcode404_camx_core_imaging_optimization_NativeSimdFusionCandidate_nativeFuseDense(
    JNIEnv* environment,
    jobject /* receiver */,
    jfloatArray signals,
    jfloatArray variances,
    jint frame_count,
    jint pixel_count,
    jfloat minimum_variance,
    jfloatArray fused_radiance,
    jfloatArray fused_variance,
    jfloatArray effective_sample_count) {
  if (frame_count <= 0 || pixel_count <= 0) return -1;
  const auto frames = static_cast<std::size_t>(frame_count);
  const auto pixels = static_cast<std::size_t>(pixel_count);
  if (frames > camx::imaging::kM9MaxFusionFrames || pixels > camx::imaging::kM9MaxFusionPixels ||
      pixels > camx::imaging::kM9MaxFusionSamples / frames) {
    return -1;
  }
  const std::size_t samples = frames * pixels;
  if (!ExactFloatArrayLength(environment, signals, samples) ||
      !ExactFloatArrayLength(environment, variances, samples) ||
      !ExactFloatArrayLength(environment, fused_radiance, pixels) ||
      !ExactFloatArrayLength(environment, fused_variance, pixels) ||
      !ExactFloatArrayLength(environment, effective_sample_count, pixels)) {
    return -1;
  }

  jfloat* signal_values = environment->GetFloatArrayElements(signals, nullptr);
  if (signal_values == nullptr) return -1;
  jfloat* variance_values = environment->GetFloatArrayElements(variances, nullptr);
  if (variance_values == nullptr) {
    environment->ReleaseFloatArrayElements(signals, signal_values, JNI_ABORT);
    return -1;
  }
  jfloat* radiance_values = environment->GetFloatArrayElements(fused_radiance, nullptr);
  if (radiance_values == nullptr) {
    environment->ReleaseFloatArrayElements(variances, variance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(signals, signal_values, JNI_ABORT);
    return -1;
  }
  jfloat* fused_variance_values = environment->GetFloatArrayElements(fused_variance, nullptr);
  if (fused_variance_values == nullptr) {
    environment->ReleaseFloatArrayElements(fused_radiance, radiance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(variances, variance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(signals, signal_values, JNI_ABORT);
    return -1;
  }
  jfloat* effective_values = environment->GetFloatArrayElements(effective_sample_count, nullptr);
  if (effective_values == nullptr) {
    environment->ReleaseFloatArrayElements(fused_variance, fused_variance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(fused_radiance, radiance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(variances, variance_values, JNI_ABORT);
    environment->ReleaseFloatArrayElements(signals, signal_values, JNI_ABORT);
    return -1;
  }

  const auto status = camx::imaging::FuseDenseInverseVariance(
      signal_values,
      variance_values,
      frames,
      pixels,
      minimum_variance,
      radiance_values,
      fused_variance_values,
      effective_values);

  environment->ReleaseFloatArrayElements(effective_sample_count, effective_values, status.ok ? 0 : JNI_ABORT);
  environment->ReleaseFloatArrayElements(fused_variance, fused_variance_values, status.ok ? 0 : JNI_ABORT);
  environment->ReleaseFloatArrayElements(fused_radiance, radiance_values, status.ok ? 0 : JNI_ABORT);
  environment->ReleaseFloatArrayElements(variances, variance_values, JNI_ABORT);
  environment->ReleaseFloatArrayElements(signals, signal_values, JNI_ABORT);
  if (!status.ok || environment->ExceptionCheck() == JNI_TRUE) return -1;
  return static_cast<jint>(status.backend);
}
