"""Exercise production framebuffer eviction while its graphics submission is pending."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT/'core/rpcs3/Emu/RSX/VK/VKFramebuffer.cpp').read_text(encoding='utf-8')
start = source.index('void remove_framebuffers_with_filter(')
begin = source.index('{', start)
depth, end = 1, begin + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1

HARNESS = r'''
#include <algorithm>
#include <cassert>
#include <concepts>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>
using u64=unsigned long long;
static u64 current_eid=6,completed_eid=4;
static unsigned destroyed=0,pending_destructions=0;
struct logger { template<class... T> void warning(T&&...) {} } rsx_log;
namespace vk {
struct framebuffer_holder {
    u64 last_use;
    bool refs=false,retired=false;
    explicit framebuffer_holder(u64 event):last_use(event) {}
    bool has_refs() const { return refs; }
    ~framebuffer_holder() { ++destroyed; if(last_use>completed_eid) ++pending_destructions; }
};
std::unordered_map<u64,std::vector<std::unique_ptr<framebuffer_holder>>> g_framebuffers_cache;
std::mutex g_framebuffers_mutex;
struct resource_manager {
    std::vector<std::pair<u64,std::unique_ptr<framebuffer_holder>>> deferred;
    void dispose(std::unique_ptr<framebuffer_holder>& holder) {
        deferred.emplace_back(current_eid,std::move(holder));
    }
    void complete(u64 eid) {
        completed_eid=eid;
        std::erase_if(deferred,[&](const auto& object) { return object.first<=eid; });
    }
} manager;
resource_manager* get_resource_manager() { return &manager; }
''' + source[start:end] + r'''
}
int main() {
    vk::g_framebuffers_cache[1].push_back(std::make_unique<vk::framebuffer_holder>(5));
    vk::remove_framebuffers_with_filter([](const auto&) { return true; });
    assert(vk::g_framebuffers_cache.empty());
    assert(destroyed==0 && pending_destructions==0);
    assert(vk::manager.deferred.size()==1);
    vk::manager.complete(5);
    assert(destroyed==0);
    vk::manager.complete(6);
    assert(destroyed==1 && pending_destructions==0);

    // A CPU owner still prevents eviction. Once released, GPU retirement remains necessary.
    current_eid=8;
    auto holder=std::make_unique<vk::framebuffer_holder>(7);
    holder->refs=true;
    auto* raw=holder.get();
    vk::g_framebuffers_cache[2].push_back(std::move(holder));
    vk::remove_framebuffers_with_filter([](const auto&) { return true; });
    assert(raw->retired && vk::g_framebuffers_cache.size()==1 && vk::manager.deferred.empty());
    raw->refs=false;
    vk::remove_framebuffers_with_filter([](const auto&) { return true; });
    assert(vk::g_framebuffers_cache.empty() && destroyed==1);
    vk::manager.complete(7);
    assert(destroyed==1);
    vk::manager.complete(8);
    assert(destroyed==2 && pending_destructions==0 && vk::manager.deferred.empty());
    vk::remove_framebuffers_with_filter([](const auto&) { return true; });
    assert(destroyed==2);
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
with tempfile.TemporaryDirectory(prefix='emucorec-framebuffer-retirement-') as directory:
    out = Path(directory)
    (out/'test.cpp').write_text(HARNESS, encoding='utf-8')
    executable = out / ('test.exe' if os.name == 'nt' else 'test')
    subprocess.run([compiler, '-std=c++20', '-fno-exceptions', '-D__ANDROID__', str(out/'test.cpp'), '-o', str(executable)], check=True, env=env)
    subprocess.run([str(executable)], check=True, env=env)
print('PASS framebuffer eviction waits for graphics event retirement and preserves CPU owners')
