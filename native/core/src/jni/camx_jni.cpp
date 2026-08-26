#include <jni.h>

#include <array>
#include <cstdint>

#include "camx/ndk_advertised_metadata.hpp"
#include "camx/resource_counters.hpp"

#if !defined(__ANDROID_API__) || __ANDROID_API__ != 23
#error "libcamx_core must be compiled for the Android API-23 baseline"
#endif

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
  if (result == nullptr) {
    return nullptr;
  }
  environment->SetLongArrayRegion(
      result,
      0,
      static_cast<jsize>(values.size()),
      values.data());
  if (environment->ExceptionCheck() == JNI_TRUE) {
    return nullptr;
  }
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahidcode404_camx_core_camera_discovery_NdkAdvertisedNativeBridge_nativeCollect(
    JNIEnv* environment,
    jobject /* receiver */,
    jint android_api) {
  const auto encoded = camx::CollectAndroidNdkAdvertisedMetadata(android_api);
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
