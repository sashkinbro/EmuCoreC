"""Compile actual save-history cleanup functions and exercise filesystem safety guards."""
from pathlib import Path
import re
import subprocess
import argparse
import os
import shutil
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'android/src/save_states.cpp').read_text(encoding='utf-8')

def extract(name):
    match = re.search(r'(?m)^    (?:bool|void|std::string) ' + name + r'\(', source)
    assert match, name
    begin = source.index('{', match.start())
    depth = 1
    end = begin + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

harness = r'''
#include <algorithm>
#include <cassert>
#include <filesystem>
#include <fstream>
#include <string>
#include <cstdint>
using u64 = uint64_t;
using s64 = int64_t;
namespace fs {
std::string config;
std::string get_config_dir() { return config; }
struct stat_t { u64 size; s64 mtime; };
bool get_stat(const std::string& path, stat_t& s) {
    std::error_code e;
    if (!std::filesystem::is_regular_file(path,e)) return false;
    s.size=std::filesystem::file_size(path);
    s.mtime=std::filesystem::last_write_time(path).time_since_epoch().count();
    return true;
}
bool remove_file(const std::string& path) {
    std::error_code e;
    return std::filesystem::remove(path,e);
}
}
struct logger { template<typename... T> void warning(T&&...) {} } save_state_log;
struct operation {
    bool save=true, captured=true, reader_released=false;
    std::string title="NPUB30505", path;
    unsigned slot=0;
    u64 copied_history_size=0;
    s64 copied_history_mtime=0;
};
std::string slot_directory(const std::string& title) {
    return fs::config+"savestates/"+title+"/emucorec_slots/";
}
'''
harness += '\n'.join(extract(name) for name in ['valid_title','history_note','owned_history_path','remove_completed_history'])
harness += r'''
int main(int argc,char**argv) {
    fs::config=std::filesystem::absolute(argv[1]).generic_string()+"/";
    const auto directory=fs::config+"savestates/NPUB30505/";
    std::filesystem::create_directories(slot_directory("NPUB30505"));
    operation op;
    op.path=directory+"NPUB30505_20261109_0.SAVESTAT.zst";
    {std::ofstream file(op.path);file<<"completed snapshot";}
    fs::stat_t s{}; assert(fs::get_stat(op.path,s));
    op.copied_history_size=s.size;op.copied_history_mtime=s.mtime;
    remove_completed_history(op);
    assert(std::filesystem::exists(op.path) && "history must survive an active restore reader");
    op.reader_released=true;op.captured=false;
    remove_completed_history(op);
    assert(std::filesystem::exists(op.path) && "failed slot copy must keep its only history");
    op.captured=true;op.copied_history_size++;
    remove_completed_history(op);
    assert(std::filesystem::exists(op.path) && "changed history must not be removed");
    op.copied_history_size=s.size;
    {std::ofstream note(history_note(op.title,op.slot));note<<"receipt";}
    remove_completed_history(op);
    assert(!std::filesystem::exists(op.path));
    assert(!std::filesystem::exists(history_note(op.title,op.slot)));
    assert(!owned_history_path("NPUB30505",directory+"emucorec_slots/slot0.SAVESTAT.zst"));
    assert(!owned_history_path("NPUB30505",directory+"../NPUB30505_outside.SAVESTAT.zst"));
    assert(!owned_history_path("NPUB30505",directory+"NPUB00000_0.SAVESTAT.zst"));
    assert(!owned_history_path("NPUB30505",op.path+std::string("\0forged.SAVESTAT.zst",20)));
}
'''
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cxx', default=os.environ.get('CXX'))
args = parser.parse_args()
compiler = args.cxx or shutil.which('g++') or shutil.which('clang++')
if not compiler:
    parser.error('Set CXX or pass --cxx with a C++20 compiler.')
env = os.environ.copy()
env['PATH'] = str(Path(compiler).resolve().parent) + os.pathsep + env.get('PATH', '')
with tempfile.TemporaryDirectory(prefix='emucorec-save-history-') as directory:
    out = Path(directory)
    (out / 'test.cpp').write_text(harness, encoding='utf-8')
    executable = out / ('test.exe' if os.name == 'nt' else 'test')
    subprocess.run([compiler, '-std=c++20', str(out / 'test.cpp'), '-o', str(executable)], check=True, env=env)
    subprocess.run([str(executable), str(out / 'fixture')], check=True, env=env)
print('Save-history cleanup passed active-reader, failed-copy, changed-file, exact-file and traversal checks.')
