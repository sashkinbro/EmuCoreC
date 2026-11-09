"""Host behavior checks for the production Android GPU memory budget policy."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r"""
#include "mobile_memory_budget.hpp"
#include <cassert>
#include <cstdint>
#include <limits>
int main() {
    using vk::mobile_memory_budget;
    constexpr std::uint64_t M = 1024 * 1024;
    assert(mobile_memory_budget::limit_for_device(2048*M, 2048*M) == 512*M);
    assert(mobile_memory_budget::limit_for_device(2048*M, 4096*M) == 1024*M);
    assert(mobile_memory_budget::limit_for_device(2048*M, 8192*M) == 2048*M);
    assert(mobile_memory_budget::limit_for_device(1024*M, 16384*M) == 1024*M);
    assert(mobile_memory_budget::limit_for_device(2048*M, 0) == 256*M);
    assert(mobile_memory_budget::system_reserve(2048*M) == 512*M);
    assert(mobile_memory_budget::system_reserve(8192*M) == 1024*M);
    assert(mobile_memory_budget::system_reserve(32768*M) == 1536*M);
    mobile_memory_budget pool(1024*M, 512*M);
    assert(pool.capacity() == 256*M && pool.used() == 0);
    assert(pool.try_reserve(128*M, 4096*M));
    assert(pool.try_reserve(256*M, 4096*M));
    assert(pool.used() == 384*M && pool.capacity() == 512*M);
    // A second heap shares the same limit, rather than receiving another 1 GiB.
    assert(!pool.try_reserve(768*M, 4096*M));
    assert(pool.used() == 384*M && pool.capacity() == 512*M);
    assert(!pool.try_reserve(M, 512*M));
    assert(!pool.try_reserve(M, 0));
    assert(!pool.try_reserve(std::numeric_limits<std::uint64_t>::max(), 4096*M));
    assert(pool.used() == 384*M);
    assert(pool.pressure(520*M) > 95.f);
    assert(pool.pressure(4096*M) < 50.f);
    pool.release(256*M); // Also used to roll back a failed Vulkan allocation.
    assert(pool.used() == 128*M && pool.capacity() == 256*M);
    pool.release(128*M);
    assert(pool.used() == 0 && pool.capacity() == 256*M);
    mobile_memory_budget small(192*M, 512*M);
    assert(small.capacity() == 192*M);
    assert(small.try_reserve(192*M, 4096*M));
    assert(!small.try_reserve(1, 4096*M));
    assert(small.pressure(4096*M) == 100.f);
    mobile_memory_budget odd(769*M, 512*M);
    assert(odd.try_reserve(769*M, 4096*M));
    assert(odd.capacity() == 769*M);
    assert(!odd.try_reserve(1, 4096*M));
}
"""

source = (ROOT/'core/rpcs3/Emu/RSX/VK/vkutils/memory.cpp').read_text(encoding='utf-8')
start = source.index('\t\tstruct mobile_pool_state')
end = source.index('\n\t}\n#endif', start)
HOOKS = r"""
#include "mobile_memory_budget.hpp"
#include <cassert>
#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <thread>
#include <atomic>
using u64 = std::uint64_t;
using VkDevice = std::uintptr_t;
using VkDeviceMemory = std::uintptr_t;
using VkResult = int;
struct VkAllocationCallbacks {};
struct VkMemoryAllocateInfo { u64 allocationSize; unsigned memoryTypeIndex = 0; };
using PFN_vkAllocateMemory = VkResult(*)(VkDevice,const VkMemoryAllocateInfo*,const VkAllocationCallbacks*,VkDeviceMemory*);
using PFN_vkFreeMemory = void(*)(VkDevice,VkDeviceMemory,const VkAllocationCallbacks*);
#define VKAPI_ATTR
#define VKAPI_CALL
constexpr VkResult VK_SUCCESS = 0;
constexpr VkResult VK_ERROR_OUT_OF_DEVICE_MEMORY = -2;
static u64 available = 4096ull << 20;
static bool driver_failure = false;
static unsigned driver_calls = 0;
static VkDeviceMemory next_handle = 0;
static std::unordered_map<VkDeviceMemory,u64> live;
namespace utils { u64 get_avail_memory() { return available; } }
struct log_t { template<class... T> void warning(T&&...) {} } rsx_log;
VkResult vkAllocateMemory(VkDevice,const VkMemoryAllocateInfo* info,const VkAllocationCallbacks*,VkDeviceMemory* out) {
    ++driver_calls;
    if (driver_failure) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *out = ++next_handle; live[*out] = info->allocationSize;
    return VK_SUCCESS;
}
void vkFreeMemory(VkDevice,VkDeviceMemory memory,const VkAllocationCallbacks*) { live.erase(memory); }
namespace vk {
""" + source[start:end] + r"""
}
int main() {
    constexpr u64 M = 1024 * 1024;
    constexpr VkDevice device = 1;
    vk::mobile_pools.try_emplace(device, 1024*M, 512*M);
    VkMemoryAllocateInfo info{128*M, 0};
    VkDeviceMemory first = 0, second = 0;
    assert(vk::mobile_allocate_memory(device,&info,nullptr,&first) == VK_SUCCESS);
    auto& budget = vk::mobile_pools.at(device).budget;
    driver_failure = true; info.allocationSize = 256*M;
    assert(vk::mobile_allocate_memory(device,&info,nullptr,&second) == VK_ERROR_OUT_OF_DEVICE_MEMORY);
    assert(budget.used() == 128*M && budget.capacity() == 256*M && live.size() == 1);
    driver_failure = false; info.allocationSize = 768*M; info.memoryTypeIndex = 6;
    assert(vk::mobile_allocate_memory(device,&info,nullptr,&second) == VK_SUCCESS);
    const auto calls = driver_calls;
    info.allocationSize = 256*M;
    assert(vk::mobile_allocate_memory(device,&info,nullptr,&first) == VK_ERROR_OUT_OF_DEVICE_MEMORY);
    assert(driver_calls == calls && budget.used() == 896*M);
    vk::mobile_free_memory(device,first,nullptr);
    available = 512*M; info.allocationSize = M;
    assert(vk::mobile_allocate_memory(device,&info,nullptr,&first) == VK_ERROR_OUT_OF_DEVICE_MEMORY);
    assert(driver_calls == calls);
    vk::mobile_free_memory(device,second,nullptr);
    assert(budget.used() == 0 && live.empty());
    available = 4096*M; info.allocationSize = 600*M;
    std::atomic<unsigned> successes{0};
    VkDeviceMemory a=0,b=0;
    auto allocate = [&](VkDeviceMemory& handle) {
        if (vk::mobile_allocate_memory(device,&info,nullptr,&handle) == VK_SUCCESS) ++successes;
    };
    std::thread one(allocate,std::ref(a)), two(allocate,std::ref(b));
    one.join(); two.join();
    assert(successes == 1 && budget.used() == 600*M && live.size() == 1);
    vk::mobile_free_memory(device,a ? a : b,nullptr);
    assert(budget.used() == 0 && live.empty());
}
"""

def extract_method(source, signature):
    start = source.index(signature)
    begin = source.index('{', start)
    depth, end = 1, begin + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

commands = (ROOT/'core/rpcs3/Emu/RSX/VK/vkutils/commands.cpp').read_text(encoding='utf-8')
chunks = (ROOT/'core/rpcs3/Emu/RSX/VK/VKGSRenderTypes.hpp').read_text(encoding='utf-8')
RETIRED_STORAGE = r"""
#include <cassert>
using u64 = unsigned long long;
using VkResult = int;
constexpr unsigned VK_COMMAND_BUFFER_RESET_RELEASE_RESOURCES_BIT = 1;
constexpr u64 FRAME_PRESENT_TIMEOUT = 1;
static unsigned reset_calls=0, reset_flags=0;
int vkResetCommandBuffer(int, unsigned flags) { ++reset_calls; reset_flags=flags; return 0; }
#define CHECK_RESULT(value) assert((value) == 0)
namespace vk {
struct command_buffer {
    bool is_pending=false, recording=false;
    int commands=1;
    bool is_recording() const { return recording; }
    void reset(bool release_resources=false);
};
""" + extract_method(commands, 'void command_buffer::reset(bool release_resources)') + r"""
struct command_buffer_chunk : command_buffer {
    bool has_retained_storage=false, signalled=false;
    u64 reset_id=0;
    bool poke() { if (is_pending && signalled) is_pending=false; return !is_pending; }
    void wait(u64) { assert(false && "memory reclaim must not wait on in-flight work"); }
""" + extract_method(chunks, 'void reset(bool release_resources = false)') + '\n' + extract_method(chunks, 'bool release_completed_storage()') + r"""
};
}
int main() {
    vk::command_buffer_chunk cb;
    assert(!cb.release_completed_storage() && reset_calls==0);
    cb.has_retained_storage=true; cb.recording=true;
    assert(!cb.release_completed_storage() && reset_calls==0);
    cb.recording=false; cb.is_pending=true;
    assert(!cb.release_completed_storage() && reset_calls==0);
    cb.signalled=true;
    assert(cb.release_completed_storage());
    assert(!cb.is_pending && !cb.has_retained_storage && cb.reset_id==1);
    assert(reset_calls==1 && reset_flags==VK_COMMAND_BUFFER_RESET_RELEASE_RESOURCES_BIT);
    assert(!cb.release_completed_storage() && reset_calls==1);
}
"""
manager = (ROOT/'core/rpcs3/Emu/RSX/VK/VKResourceManager.cpp').read_text(encoding='utf-8')
# Select the Android signature; the opening brace follows the Apple/Android signature guard.
severity_body = extract_method(manager, 'rsx::problem_severity vmm_determine_memory_load_severity()')
# Its signature's #endif belongs to the surrounding declaration guard.
severity_body = severity_body[:severity_body.index('\n')] + '\n' + severity_body[severity_body.index('{'):]
PRESSURE_SEVERITY = r"""
#define __ANDROID__ 1
#include "mobile_memory_budget.hpp"
#include <cassert>
using u64 = unsigned long long;
constexpr u64 M=1024*1024;
namespace rsx {
enum class problem_severity { low, moderate, severe, fatal };
struct Renderer { struct Scaling { float scale_factor() { return 1; } } resolution_scaling_config; } renderer;
Renderer* get_current_renderer() { return &renderer; }
}
static u64 available=4096*M, application=400*M;
namespace utils { u64 get_total_memory() { return 8192*M; } u64 get_avail_memory() { return available; } }
struct Allocator {
    float load=0; float get_memory_usage() { return load; }
    void set_fastest_allocation_flags() {} void set_safest_allocation_flags() {}
} allocator;
auto get_current_mem_allocator() { return &allocator; }
struct Mapping { int device_local=0; u64 device_local_total_bytes=4096*M; };
struct Renderer { Mapping get_memory_mapping() { return {}; } } renderer;
auto get_current_renderer() { return &renderer; }
u64 vmm_get_application_memory_usage_impl(int) { return application; }
struct { int mutex; } g_vmm_stats;
struct reader_lock { reader_lock(int&) {} };
struct { template<class... T> void warning(T...) {} } rsx_log;
namespace vk {
""" + severity_body + r"""
}
int main() {
    using rsx::problem_severity;
    allocator.load=96; // Aggregate pool is full despite a much larger reported heap.
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::fatal);
    allocator.load=92;
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::severe);
    allocator.load=80;
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::moderate);
    allocator.load=10; available=256*M;
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::fatal);
    available=1100*M;
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::moderate);
    available=4096*M;
    assert(vk::vmm_determine_memory_load_severity()==problem_severity::low);
}
"""

render = (ROOT/'core/rpcs3/Emu/RSX/VK/VKGSRender.cpp').read_text(encoding='utf-8')
reclaim = extract_method(render, 'bool VKGSRender::on_vram_exhausted(rsx::problem_severity severity)')
# Exercise the production uninterruptible early return with no cache able to relieve pressure.
reclaim = reclaim[:reclaim.index('\n\tconst auto resources = res();', reclaim.index('return relieved;') if 'return relieved;' in reclaim else reclaim.index('return command_storage_relieved'))] + '\n return false;\n}'
RECLAIM_RETRY = r"""
#define __ANDROID__ 1
#include <algorithm>
#include <cassert>
#define ensure(value) assert(value)
namespace rsx {
enum class problem_severity { low, moderate, severe, fatal };
struct Renderer { bool is_current_thread() { return true; } } renderer;
auto get_current_renderer() { return &renderer; }
}
namespace vk { bool is_uninterruptible() { return true; } bool is_last_ditch_eviction() { return false; } }
struct { template<class... T> void warning(T...) {} } rsx_log;
struct Resources { struct Cache { bool handle_memory_pressure(rsx::problem_severity) { return false; } } texture_cache; };
struct VKGSRender {
    struct Chain { bool released=true; bool release_completed_storage() { return released; } } m_primary_cb_list;
    Resources* res() { return nullptr; }
    bool on_vram_exhausted(rsx::problem_severity severity);
};
""" + reclaim + r"""
int main() {
    VKGSRender renderer;
    assert(renderer.on_vram_exhausted(rsx::problem_severity::severe));
    renderer.m_primary_cb_list.released=false;
    assert(!renderer.on_vram_exhausted(rsx::problem_severity::severe));
}
"""

sysinfo = (ROOT/'core/rpcs3/util/sysinfo.cpp').read_text(encoding='utf-8')
AVAILABLE_MEMORY = r"""
#define __linux__ 1
#include <cassert>
#include <cstdio>
#include <fstream>
#include <string>
#include <limits>
using u64=unsigned long long;
using usz=std::size_t;
constexpr usz umax=std::string::npos;
// procfs reports st_size=0. The former size-based File wrapper returned no bytes.
namespace fs { struct file {
    file(const char*) {} explicit operator bool() const { return true; }
    std::string to_string() { return {}; }
}; }
namespace utils { u64 get_avail_memory(); }
""" + extract_method(sysinfo, 'u64 utils::get_avail_memory()').replace('"/proc/meminfo"', '"meminfo-fixture"') + r"""
int main() {
    std::ofstream("meminfo-fixture") << "MemTotal: 7762656 kB\nMemFree: 4000 kB\nMemAvailable: 4088264 kB\n";
    assert(utils::get_avail_memory()==4088264ull*1024);
    std::ofstream("meminfo-fixture") << "MemAvailable: 0 kB\n";
    assert(utils::get_avail_memory()==0);
    std::ofstream("meminfo-fixture") << "MemAvailable: malformed\n";
    assert(utils::get_avail_memory()==0);
    std::remove("meminfo-fixture");
    assert(utils::get_avail_memory()==0);
}
"""

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cxx', default=os.environ.get('CXX'))
args = parser.parse_args()
compiler = args.cxx or shutil.which('g++') or shutil.which('clang++')
if not compiler:
    parser.error('Pass --cxx with a C++20 compiler.')
env = os.environ.copy()
env['PATH'] = str(Path(compiler).resolve().parent) + os.pathsep + env.get('PATH', '')
with tempfile.TemporaryDirectory(prefix='emucorec-gpu-budget-') as directory:
    out = Path(directory)
    for name, code in [('available-system-memory', AVAILABLE_MEMORY), ('policy', HARNESS), ('allocation-hooks', HOOKS), ('retired-command-storage', RETIRED_STORAGE), ('reclaim-retry', RECLAIM_RETRY), ('pressure-severity', PRESSURE_SEVERITY)]:
        (out/'test.cpp').write_text(code, encoding='utf-8')
        executable = out / (name + ('.exe' if os.name == 'nt' else ''))
        subprocess.run([compiler, '-std=c++20', '-pthread', '-I', str(ROOT/'core/rpcs3/Emu/RSX/VK/vkutils'), str(out/'test.cpp'), '-o', str(executable)], check=True, env=env)
        subprocess.run([str(executable)], check=True, env=env, cwd=out)
        print('PASS', name)
print('PASS GPU budget growth, shrink, shared limit, rollback, headroom, pressure and overflow guards')
