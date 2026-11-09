#!/usr/bin/env python3
"""Host regression for Android RSX protections; run with Python 3 and a C++20 compiler.

Extracts the production MM queue, protection mirror, address ranges, and six caller
statements on every run. Only OS protection and unrelated emulator services are
replaced; there is no second implementation of the protection algorithm.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def source(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def caller_statements(relative):
    lines = source(relative).splitlines()
    result = []
    for i, line in enumerate(lines):
        if not re.search(r"(?:utils::memory_protect|rsx::mm_protect_immediate)\(vm::base\(", line):
            continue
        # Preserve the actual platform branch, when present. Skip the desktop
        # alternative, rather than substituting an Android call in the test.
        if i and lines[i - 1].strip() == "#else":
            continue
        start, end = i, i + 1
        if i and lines[i - 1].strip() == "#ifdef __ANDROID__":
            start = i - 1
            end = next(j + 1 for j in range(i + 1, len(lines)) if lines[j].strip() == "#endif")
        expected = "no" if "protection::no" in line else "rw"
        result.append(("\n".join(lines[start:end]), expected))
    return result


PRELUDE = r'''
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <vector>
using u8=uint8_t; using u16=uint16_t; using u32=uint32_t; using u64=uint64_t; using usz=size_t;
constexpr u64 umax=~u64{0};
#define CHECK(x) do { if (!(x)) throw std::runtime_error(#x); } while (0)
#define AUDIT(x) CHECK(x)
#define ensure(x) CHECK(x)
// FN takes an expression whose parameter is named x in production.
#define FN(expr) [&](const auto& x) { return (expr); }
struct shared_mutex {
    bool held=false;
    void lock() { CHECK(!held); held=true; }
    void unlock() { CHECK(held); held=false; }
};
namespace fmt { template<class... T> std::string format(const char*, T...) { return {}; } }
namespace vm { void* base(u32 address) { return reinterpret_cast<void*>(0x1000000000ull+address); } }
namespace utils {
    enum class protection { no, ro, rw, wx };
    size_t get_page_size() { return 4096; }
    struct operation { u64 start, length; protection prot; bool locked; };
    std::vector<operation> operations;
    std::array<protection,16> os_pages{};
    void memory_protect(void*, u64, protection);
}
struct { struct { bool disable_async_host_memory_manager=false; } video; } g_cfg;
namespace rsx {
    template<class T> struct simple_array : std::vector<T> {
        using std::vector<T>::vector;
        template<class F> bool any(F&& f) const {
            return std::any_of(this->begin(),this->end(),std::forward<F>(f));
        }
    };
    u64 get_shared_tag() { return 1; }
}
'''

SUPPORT = r'''
namespace utils {
void memory_protect(void* ptr, u64 length, protection prot) {
    const u64 start=reinterpret_cast<u64>(ptr);
    operations.push_back({start,length,prot,rsx::g_mprotect_queue_lock.held});
    const auto first=(start-reinterpret_cast<u64>(vm::base(0)))/4096;
    CHECK(first+length/4096<=os_pages.size());
    for (u64 p=first;p<first+length/4096;++p) os_pages[p]=prot;
}
}
void reset() {
    rsx::g_deferred_mprotect_queue.clear(); utils::operations.clear();
    utils::os_pages.fill(utils::protection::rw);
    for (auto& p:rsx::g_guest_page_protection) p.store(static_cast<u8>(utils::protection::rw));
}
void expect_page(u32 address, utils::protection prot) {
    CHECK(utils::os_pages[address/4096]==prot);
    CHECK(rsx::mm_is_accessible(address,false)==(prot!=utils::protection::no));
    CHECK(rsx::mm_is_accessible(address,true)==(prot==utils::protection::rw));
}
void protect_immediate(void* ptr,u64 length,utils::protection prot) {
    rsx::mm_protect_immediate(ptr,length,prot);
}
'''


def translation_unit():
    mm = source("core/rpcs3/Emu/RSX/Host/MM.cpp")
    header = source("core/rpcs3/Emu/RSX/Host/MM.h")
    # Reject a missing public API before the compiler, with a useful regression
    # failure instead of a large set of unrelated generated-code diagnostics.
    assert "void mm_protect_immediate(" in header, "missing immediate-protection public API"
    assert "void mm_protect_immediate(" in mm, "missing immediate-protection implementation"
    ranges = source("core/Utilities/address_range.h")
    ranges = ranges[ranges.index("namespace utils"):ranges.index("\n\ttemplate <typename T>\n\tstatic inline address_range<T> page_for")]+"\n}\n"
    block = header[header.index("namespace rsx"):header.index("\n\tbool mm_is_accessible")]+"\n}\n"
    queue = mm[mm.index("namespace rsx"):mm.index("\n\tvoid mm_flush_lazy()")]+"\n}\n"
    text = PRELUDE + ranges + block + queue + SUPPORT
    callers = caller_statements("core/rpcs3/Emu/RSX/Common/texture_cache.h")
    callers += caller_statements("core/rpcs3/Emu/RSX/RSXZCULL.cpp")
    assert len(callers) == 6, f"expected six production caller paths, got {len(callers)}"
    for index, (statement, _) in enumerate(callers):
        text += f"\nvoid caller_{index}(u32 address) {{\n"
        text += "const auto prot_range=utils::address_range64::start_length(address,4096);\n"
        text += "const auto p=std::pair{address,0}; const auto page_address=address; const auto this_address=address;\n"
        text += statement + "\n}\n"
    text += r'''
int failures=0;
template<class F> void test(const char* name,F action) {
    try { reset(); action(); std::cout<<"PASS "<<name<<'\n'; }
    catch (const std::exception& e) { ++failures; std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n'; }
}
int main() {
    test("immediate NO updates OS and guest mirror",[] {
        protect_immediate(vm::base(4096),4096,utils::protection::no);
        expect_page(4096,utils::protection::no);
        CHECK(utils::operations.back().locked);
        CHECK(!rsx::g_mprotect_queue_lock.held);
    });
    test("immediate RW drains older overlapping queue before unlock",[] {
        // Partially overlapping range: the older protection must be applied
        // before RW, and cannot survive to overwrite it on a later flush.
        rsx::mm_protect(vm::base(0),8192,utils::protection::ro);
        protect_immediate(vm::base(4096),4096,utils::protection::rw);
        CHECK(utils::operations.size()==2);
        CHECK(utils::operations[0].prot==utils::protection::ro);
        CHECK(utils::operations[1].prot==utils::protection::rw);
        for (const auto& op:utils::operations) CHECK(op.locked);
        expect_page(0,utils::protection::ro);
        expect_page(4096,utils::protection::rw);
        rsx::mm_flush();
        expect_page(4096,utils::protection::rw);
    });
    test("unrelated queued page retains its intended protection",[] {
        rsx::mm_protect(vm::base(12288),4096,utils::protection::no);
        protect_immediate(vm::base(4096),4096,utils::protection::rw);
        expect_page(4096,utils::protection::rw);
        rsx::mm_flush();
        expect_page(12288,utils::protection::no);
        expect_page(4096,utils::protection::rw);
    });
'''
    for index, (_, prot) in enumerate(callers):
        # Callers execute their real statement. An old raw OS call fails mirror
        # validation (NO) or is overwritten when the old queued NO is flushed (RW).
        setup = "" if prot == "no" else "rsx::mm_protect(vm::base(0),8192,utils::protection::no);"
        text += f'''test("production caller {index + 1} ({prot})",[] {{
            {setup}
            caller_{index}(4096);
            expect_page(4096,utils::protection::{prot});
            CHECK(utils::operations.back().locked);
            rsx::mm_flush();
            expect_page(4096,utils::protection::{prot});
        }});\n'''
    return text + "return failures?1:0;\n}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX") or shutil.which("g++") or shutil.which("clang++"))
    args = parser.parse_args()
    if not args.cxx:
        parser.error("set CXX or pass --cxx with a C++20 host compiler")
    try:
        unit = translation_unit()
    except AssertionError as error:
        print(f"FAIL: {error}")
        return 1
    with tempfile.TemporaryDirectory(prefix="emucorec-rsx-mm-") as temp:
        cpp = Path(temp) / "rsx_mm.cpp"
        exe = Path(temp) / ("rsx_mm.exe" if os.name == "nt" else "rsx_mm")
        cpp.write_text(unit, encoding="utf-8")
        env = dict(os.environ)
        env["PATH"] = str(Path(args.cxx).resolve().parent) + os.pathsep + env.get("PATH", "")
        subprocess.run([args.cxx, "-std=c++20", "-D__ANDROID__", "-O0", str(cpp), "-o", str(exe)], check=True, env=env)
        return subprocess.run([str(exe)], env=env).returncode


if __name__ == "__main__":
    raise SystemExit(main())
