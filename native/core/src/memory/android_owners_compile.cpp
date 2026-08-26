#include "camx/android_owners.hpp"

#include <type_traits>

namespace {

struct CompileOnlyHandle final {};

void ReleaseCompileOnlyHandle(CompileOnlyHandle* handle) noexcept { delete handle; }

using CompileOnlyOwner = camx::UniqueNdkOwner<CompileOnlyHandle, ReleaseCompileOnlyHandle>;

static_assert(!std::is_copy_constructible_v<CompileOnlyOwner>);
static_assert(!std::is_copy_assignable_v<CompileOnlyOwner>);
static_assert(std::is_nothrow_move_constructible_v<CompileOnlyOwner>);
static_assert(std::is_nothrow_move_assignable_v<CompileOnlyOwner>);

}  // namespace
