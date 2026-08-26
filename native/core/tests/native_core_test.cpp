#include <cassert>
#include <cstdint>
#include <limits>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "camx/android_owners.hpp"
#include "camx/bounded_timestamp_index.hpp"
#include "camx/native_buffer_pool.hpp"
#include "camx/native_trace_buffer.hpp"
#include "camx/ndk_advertised_metadata.hpp"
#include "camx/resource_counters.hpp"

namespace {

struct TestHandle final {
  int* releases;
};

void ReleaseTestHandle(TestHandle* handle) noexcept {
  ++(*handle->releases);
  delete handle;
}

using TestOwner = camx::UniqueNdkOwner<TestHandle, ReleaseTestHandle>;

class FakeNdkSource final : public camx::NdkAdvertisedMetadataSource {
 public:
  bool available = true;
  bool enumerate = true;
  std::vector<std::string> ids;
  std::map<std::string, camx::NdkAdvertisedRecord> records;
  std::set<std::string> failed_ids;
  std::map<std::string, int> reads;

  [[nodiscard]] bool runtime_available() const noexcept override { return available; }

  bool camera_ids(std::vector<std::string>& output) noexcept override {
    if (!enumerate) return false;
    output = ids;
    return true;
  }

  bool read(std::string_view id, camx::NdkAdvertisedRecord& output) noexcept override {
    const std::string key(id);
    ++reads[key];
    if (failed_ids.contains(key)) return false;
    const auto found = records.find(key);
    if (found == records.end()) return false;
    output = found->second;
    return true;
  }
};

camx::NdkAdvertisedRecord Record(std::string id) {
  camx::NdkAdvertisedRecord record;
  record.transport_id = std::move(id);
  record.facing = 1;
  record.focal_lengths_mm = {4.2F};
  record.sensor_width_mm = 5.6F;
  record.sensor_height_mm = 4.2F;
  record.active_array = camx::NdkSize{4000, 3000};
  record.pixel_array = camx::NdkSize{4032, 3024};
  record.sensor_orientation_degrees = 90;
  record.apertures = {1.8F};
  record.color_filter_arrangement = 0;
  record.preview_streams = {{camx::NdkSize{1920, 1080}, 33333333}};
  record.fps_ranges = {{15, 30}};
  record.raw_sizes = {{4000, 3000}};
  return record;
}

void TestNdkApiUnavailable() {
  FakeNdkSource source;
  source.available = false;
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(!report.runtime_available);
  assert(report.records.empty());
  assert(report.failures.empty());
}

void TestNdkEmptyCameraList() {
  FakeNdkSource source;
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.runtime_available);
  assert(report.records.empty());
  assert(report.failures.empty());
}

void TestNdkOneAdvertisedCamera() {
  FakeNdkSource source;
  source.ids = {"rear-token"};
  source.records.emplace("rear-token", Record("rear-token"));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.size() == 1U);
  assert(report.records.front().transport_id == "rear-token");
}

void TestNdkMultipleAdvertisedCameras() {
  FakeNdkSource source;
  source.ids = {"opaque-b", "opaque-a"};
  source.records.emplace("opaque-a", Record("opaque-a"));
  source.records.emplace("opaque-b", Record("opaque-b"));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.size() == 2U);
}

void TestNdkDuplicateIdsReadOnce() {
  FakeNdkSource source;
  source.ids = {"same", "same", "same"};
  source.records.emplace("same", Record("same"));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.size() == 1U);
  assert(source.reads["same"] == 1);
}

void TestNdkMalformedIdAndCount() {
  FakeNdkSource invalid_id;
  invalid_id.ids = {std::string(camx::kNdkMaxIdBytes + 1U, 'x')};
  const auto invalid_report = camx::CollectNdkAdvertisedMetadata(invalid_id);
  assert(invalid_report.records.empty());
  assert(invalid_report.failures.size() == 1U);
  assert(invalid_report.failures.front().kind == camx::NdkAdvertisedFailureKind::kInvalidCameraId);

  FakeNdkSource overflow;
  for (std::size_t index = 0; index <= camx::kNdkMaxCameraIds; ++index) {
    overflow.ids.push_back("opaque-" + std::to_string(index));
  }
  const auto overflow_report = camx::CollectNdkAdvertisedMetadata(overflow);
  assert(overflow_report.records.empty());
  assert(overflow_report.failures.front().kind ==
         camx::NdkAdvertisedFailureKind::kCameraIdLimitExceeded);
}

void TestNdkMissingOptionalTags() {
  FakeNdkSource source;
  source.ids = {"sparse"};
  camx::NdkAdvertisedRecord sparse;
  sparse.transport_id = "sparse";
  source.records.emplace("sparse", sparse);
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.size() == 1U);
  assert(report.failures.empty());
}

template <typename Mutator>
void ExpectBoundFailure(Mutator mutate) {
  FakeNdkSource source;
  source.ids = {"bounded"};
  auto record = Record("bounded");
  mutate(record);
  source.records.emplace("bounded", std::move(record));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.empty());
  assert(report.failures.size() == 1U);
  assert(report.failures.front().kind ==
         camx::NdkAdvertisedFailureKind::kMetadataBoundExceeded);
}

void TestNdkFocalBound() {
  ExpectBoundFailure([](auto& record) {
    record.focal_lengths_mm.assign(camx::kNdkMaxFocalLengths + 1U, 1.0F);
  });
}

void TestNdkApertureBound() {
  ExpectBoundFailure([](auto& record) {
    record.apertures.assign(camx::kNdkMaxApertures + 1U, 1.8F);
  });
}

void TestNdkPreviewStreamBound() {
  ExpectBoundFailure([](auto& record) {
    record.preview_streams.assign(
        camx::kNdkMaxPreviewStreams + 1U,
        camx::NdkPreviewStream{camx::NdkSize{640, 480}, std::nullopt});
  });
}

void TestNdkFpsRangeBound() {
  ExpectBoundFailure([](auto& record) {
    record.fps_ranges.assign(camx::kNdkMaxFpsRanges + 1U, camx::NdkFpsRange{15, 30});
  });
}

void TestNdkRawSizeBound() {
  ExpectBoundFailure([](auto& record) {
    record.raw_sizes.assign(camx::kNdkMaxRawSizes + 1U, camx::NdkSize{4000, 3000});
  });
}

void TestNdkMalformedMetadataEntry() {
  FakeNdkSource source;
  source.ids = {"bad"};
  auto bad = Record("bad");
  bad.sensor_orientation_degrees = 45;
  source.records.emplace("bad", std::move(bad));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.empty());
  assert(report.failures.front().kind == camx::NdkAdvertisedFailureKind::kMalformedMetadata);
}

void TestNdkPerIdMetadataFailure() {
  FakeNdkSource source;
  source.ids = {"broken"};
  source.failed_ids.insert("broken");
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.empty());
  assert(report.failures.front().kind == camx::NdkAdvertisedFailureKind::kMetadataUnavailable);
}

void TestNdkOneFailsAnotherSucceeds() {
  FakeNdkSource source;
  source.ids = {"broken", "valid"};
  source.failed_ids.insert("broken");
  source.records.emplace("valid", Record("valid"));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  assert(report.records.size() == 1U);
  assert(report.records.front().transport_id == "valid");
  assert(report.failures.size() == 1U);
}

void TestNdkDeterministicOrdering() {
  FakeNdkSource first;
  first.ids = {"zeta", "alpha", "middle"};
  FakeNdkSource second;
  second.ids = {"middle", "zeta", "alpha"};
  for (const char* id : {"zeta", "alpha", "middle"}) {
    first.records.emplace(id, Record(id));
    second.records.emplace(id, Record(id));
  }
  const auto first_report = camx::CollectNdkAdvertisedMetadata(first);
  const auto second_report = camx::CollectNdkAdvertisedMetadata(second);
  assert(first_report == second_report);
  assert(camx::EncodeNdkAdvertisedReport(first_report) ==
         camx::EncodeNdkAdvertisedReport(second_report));
}

void TestNdkRepeatedEncodeStable() {
  FakeNdkSource source;
  source.ids = {"repeat"};
  source.records.emplace("repeat", Record("repeat"));
  const auto report = camx::CollectNdkAdvertisedMetadata(source);
  const auto first = camx::EncodeNdkAdvertisedReport(report);
  const auto second = camx::EncodeNdkAdvertisedReport(report);
  assert(first.has_value());
  assert(first == second);
  assert(first->size() <= camx::kNdkMaxEncodedBytes);
}

void TestRuntimeNdkOwnerCleanup() {
  int releases = 0;
  {
    camx::RuntimeNdkOwner<TestHandle> owner(
        new TestHandle{.releases = &releases}, ReleaseTestHandle);
    owner.reset(new TestHandle{.releases = &releases}, ReleaseTestHandle);
    assert(releases == 1);
    camx::RuntimeNdkOwner<TestHandle> moved(std::move(owner));
    assert(!owner);
    assert(moved);
  }
  assert(releases == 2);
}

void RunNdkAdvertisedTests() {
  TestNdkApiUnavailable();
  TestNdkEmptyCameraList();
  TestNdkOneAdvertisedCamera();
  TestNdkMultipleAdvertisedCameras();
  TestNdkDuplicateIdsReadOnce();
  TestNdkMalformedIdAndCount();
  TestNdkMissingOptionalTags();
  TestNdkFocalBound();
  TestNdkApertureBound();
  TestNdkPreviewStreamBound();
  TestNdkFpsRangeBound();
  TestNdkRawSizeBound();
  TestNdkMalformedMetadataEntry();
  TestNdkPerIdMetadataFailure();
  TestNdkOneFailsAnotherSucceeds();
  TestNdkDeterministicOrdering();
  TestNdkRepeatedEncodeStable();
  TestRuntimeNdkOwnerCleanup();
}

}  // namespace

int main() {
  camx::BoundedTimestampIndex<int> index(2U);
  assert(!index.insert(10, 1).has_value());
  assert(!index.insert(20, 2).has_value());
  const auto discarded = index.insert(30, 3);
  assert(discarded.has_value() && discarded.value() == 1);
  assert(!index.take(10).has_value());
  assert(index.take(20).value() == 2);

  bool rejected_zero = false;
  try {
    camx::BoundedTimestampIndex<int> invalid(0U);
  } catch (const std::invalid_argument&) {
    rejected_zero = true;
  }
  assert(rejected_zero);

  camx::NativeTraceBuffer trace(2U);
  trace.push({1, 100, 1});
  trace.push({2, 200, 1});
  trace.push({3, 300, 2});
  const auto snapshot = trace.snapshot();
  assert(snapshot.size() == 2U);
  assert(snapshot[0].code == 2);
  assert(snapshot[1].code == 3);

  camx::ResourceCounters counters;
  counters.add(camx::NativeResource::kImages, 2);
  counters.add(camx::NativeResource::kImages, -3);
  counters.add(camx::NativeResource::kBufferBytes, std::numeric_limits<std::int64_t>::max());
  counters.add(camx::NativeResource::kBufferBytes, 1);
  const auto resources = counters.snapshot();
  assert(resources[static_cast<std::size_t>(camx::NativeResource::kImages)] == 0);
  assert(resources[static_cast<std::size_t>(camx::NativeResource::kBufferBytes)] ==
         std::numeric_limits<std::int64_t>::max());

  camx::NativeBufferPool pool(64U, 2U);
  auto first_buffer = pool.acquire();
  auto second_buffer = pool.acquire();
  assert(first_buffer.has_value());
  assert(second_buffer.has_value());
  assert(!pool.acquire().has_value());
  first_buffer.reset();
  assert(pool.acquire().has_value());

  int releases = 0;
  {
    TestOwner first(new TestHandle{.releases = &releases});
    TestOwner moved(std::move(first));
    assert(!first);
    assert(moved);
    moved.reset(new TestHandle{.releases = &releases});
    assert(releases == 1);
    TestHandle* released = moved.release();
    assert(!moved);
    assert(releases == 1);
    ReleaseTestHandle(released);
    assert(releases == 2);
  }
  assert(releases == 2);

  RunNdkAdvertisedTests();
  return 0;
}
