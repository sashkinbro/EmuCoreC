"""Host regression for production LSFG allocation refusal and its recovery boundary."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
VK = ROOT / 'core/rpcs3/Emu/RSX/VK'


def method(source, signature, body_marker=None):
    start = source.index(signature)
    begin = source.index('{', start) if body_marker is None else source.rfind('{', start, source.index(body_marker, start))
    depth, end = 1, begin + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


compat = (VK / 'FrameGen/LsfgVkCompat.cpp').read_text(encoding='utf-8')
framegen = (VK / 'VKFrameGen.cpp').read_text(encoding='utf-8')
process_source = (VK / 'FrameGen/FrameGen.cpp').read_text(encoding='utf-8')
boundary = method(framegen, 'if (!g_native.framegen->Process(', 'framegen_log.error("Frame generation allocation failed:')

HARNESS = r'''
#include <cassert>
#include <algorithm>
#include <memory>
#include <optional>
#include <utility>
#include <vector>
using u64 = unsigned long long;
using f32 = float;
using VkResult = int;
using VkDeviceSize = unsigned long long;
using VkImage = int;
using VkBuffer = int;
using VmaAllocation = int;
using VmaAllocator = int;
constexpr int VK_SUCCESS=0, VK_NULL_HANDLE=0, VK_ERROR_OUT_OF_DEVICE_MEMORY=-2;
constexpr int VMA_MEMORY_USAGE_GPU_ONLY=1, VMA_MEMORY_USAGE_CPU_TO_GPU=2, VMA_MEMORY_USAGE_GPU_TO_CPU=3;
constexpr int VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT=1, VMA_ALLOCATION_CREATE_MAPPED_BIT=2;
constexpr int VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT=1, VK_FORMAT_R8G8B8A8_UNORM=1;
struct VkImageCreateInfo {};
struct VkBufferCreateInfo { VkDeviceSize size=16; };
struct VkExtent2D { unsigned width=1, height=1; };
struct VmaAllocationCreateInfo { int usage=0,flags=0,requiredFlags=0; };
struct VmaAllocationInfo { void* pMappedData=nullptr; };
struct logger { template<class... T> void error(T&&...) {} } framegen_log;
static int calls=0, fail_at=0, live=0, cleanup_calls=0;
static int copies=0;
static bool use_buffer=false, disabled=false, idle=false;
int vmaCreateImage(int,const VkImageCreateInfo*,const VmaAllocationCreateInfo*,int* image,int* alloc,void*) {
    if (++calls==fail_at) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *image=*alloc=calls; return VK_SUCCESS;
}
int vmaCreateBuffer(int,const VkBufferCreateInfo*,const VmaAllocationCreateInfo*,int* buffer,int* alloc,VmaAllocationInfo*) {
    if (++calls==fail_at) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *buffer=*alloc=calls; return VK_SUCCESS;
}
namespace vk::lsfg { bool enabled() { return true; } }
namespace Vulkan {
enum class MemoryUsage { Upload, Download };
namespace vk {
using CommandBuffer=int;
struct Image {
    int handle=0;
    Image()=default;
    Image(int,int image,int):handle(image) { ++live; }
    Image(const Image&)=delete;
    Image(Image&& other):handle(std::exchange(other.handle,0)) {}
    ~Image() { if(handle) { assert(idle); --live; } }
    explicit operator bool() const { return handle!=0; }
};
struct Buffer : Image {
    Buffer()=default;
    Buffer(int allocator,int buffer,int alloc,void*,VkDeviceSize):Image(allocator,buffer,alloc) {}
};
}
struct MemoryAllocator {
    int m_allocator=1;
    mutable VkResult m_last_error=VK_SUCCESS;
    VkResult GetLastError() const { return m_last_error; }
    vk::Image CreateImage(const VkImageCreateInfo&) const;
    vk::Buffer CreateBuffer(const VkBufferCreateInfo&,MemoryUsage) const;
};
''' + method(compat, 'vk::Image MemoryAllocator::CreateImage(') + '\n' + method(compat, 'vk::Buffer MemoryAllocator::CreateBuffer(') + r'''
using Device=int;
using VkImageView=int;
using VkFormat=int;
constexpr unsigned LSFG_REQUIRED_FRAMES=1,LSFG_RECURRENCE_FRAMES=1;
struct Shader { explicit Shader(const Device&) {} bool IsValid() const { return true; } };
struct Chain {
    std::vector<vk::Image> images;
    std::optional<vk::Buffer> buffer;
    int Input(u64) const { assert(!images.empty() && images[0]); return images[0].handle; }
    void DispatchShared(int,u64) {}
};
float ConfiguredFlowScale(VkExtent2D,VkExtent2D) { return 1; }
void CopyPresentedFrame(int,int,int,VkExtent2D) { ++copies; }
struct FrameGen {
    MemoryAllocator& memory_allocator;
    std::optional<Chain> chain;
    std::optional<Shader> shaders;
    bool generated=false,unavailable=false;
    VkExtent2D peak_guest_extent{},built_extent{};
    int built_format=0;
    float built_flow_scale=0;
    u64 frame_count=0,last_count=0,last_generations=0;
    unsigned warm_streak=0;
    struct { bool warm=false; unsigned generations=0; } plan;
    explicit FrameGen(MemoryAllocator& value):memory_allocator(value) {}
    void WaitForIdle() { idle=true; }
    void Rebuild(const Device&,VkExtent2D,int,float) {
        WaitForIdle(); chain.emplace();
        chain->images.push_back(memory_allocator.CreateImage({}));
        if (use_buffer) chain->buffer.emplace(memory_allocator.CreateBuffer({},MemoryUsage::Upload));
        else chain->images.push_back(memory_allocator.CreateImage({}));
    }
    bool Process(const Device&,int,int,int,VkExtent2D,int,VkExtent2D);
};
''' + method(process_source, 'bool FrameGen::Process(') + r'''
}
struct native_stack {
    std::unique_ptr<Vulkan::MemoryAllocator> allocator;
    std::unique_ptr<Vulkan::FrameGen> framegen;
    std::unique_ptr<int> device;
} g_native;
struct output_image { int view() const { return 1; } } g_shared_out[1];
int g_source_image=1;
unsigned g_guest_w=1,g_guest_h=1;
void disable(const char*) { disabled=true; }
void destroy_native_stack() {
    assert(idle && live==0);
    ++cleanup_calls; g_native.framegen.reset(); g_native.device.reset(); g_native.allocator.reset();
}
int run() {
    const int cmdbuf=1;
    const VkExtent2D extent{1,1};
''' + boundary + r'''
    return 1;
}
int main() {
    for (bool buffer : {false,true}) {
        calls=0; fail_at=2; idle=false; disabled=false; use_buffer=buffer;
        const int previous_cleanup=cleanup_calls;
        g_native.allocator=std::make_unique<Vulkan::MemoryAllocator>();
        g_native.framegen=std::make_unique<Vulkan::FrameGen>(*g_native.allocator);
        g_native.device=std::make_unique<int>(1);
        assert(run()==0 && disabled);
        assert(live==0 && calls==2 && copies==0);
        assert(cleanup_calls==previous_cleanup+1 && !g_native.framegen);
    }
    calls=0; fail_at=0; disabled=false;
    g_native.allocator=std::make_unique<Vulkan::MemoryAllocator>();
    g_native.framegen=std::make_unique<Vulkan::FrameGen>(*g_native.allocator);
    g_native.device=std::make_unique<int>(1);
    assert(run()==1 && !disabled && calls==2 && live==2 && copies==1);
    // A refused allocator stays failed and never asks the driver again.
    fail_at=calls+1;
    const int before=calls;
    assert(!g_native.allocator->CreateImage({}));
    assert(g_native.allocator->GetLastError()==VK_ERROR_OUT_OF_DEVICE_MEMORY);
    assert(!g_native.allocator->CreateBuffer({},Vulkan::MemoryUsage::Upload));
    assert(calls==before+1);
}
'''

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cxx', default=os.environ.get('CXX'))
args = parser.parse_args()
compiler = args.cxx or shutil.which('g++') or shutil.which('clang++')
if not compiler:
    parser.error('Pass --cxx with a C++20 host compiler.')
env = os.environ.copy()
env['PATH'] = str(Path(compiler).resolve().parent) + os.pathsep + env.get('PATH', '')
with tempfile.TemporaryDirectory(prefix='emucorec-framegen-allocation-') as directory:
    out = Path(directory)
    (out/'test.cpp').write_text(HARNESS, encoding='utf-8')
    executable = out / ('test.exe' if os.name == 'nt' else 'test')
    subprocess.run([compiler, '-std=c++20', '-fno-exceptions', str(out/'test.cpp'), '-o', str(executable)], check=True, env=env)
    subprocess.run([str(executable)], check=True, env=env)
print('PASS framegen image/buffer refusal aborts partial construction and disables generation')
