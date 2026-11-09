#pragma once

#include <mutex>
#include <string>

extern std::mutex g_emu_lifecycle_mutex;
void report_native_operation_result(long progress_id, bool success, std::string message);

namespace save_states
{
    // Both use only the short bookkeeping lock, independently of file I/O and boot.
    bool busy();
    bool defer_stop();
    // App-owned numbered slots require their committed integrity metadata.
    // External RPCS3 states keep the core's existing compatibility policy.
    bool validate_slot_file(const std::string& path);
    // Startup only, while fully stopped: reclaim authenticated copies left by a killed process.
    void cleanup_history();
    void completed(bool success, bool game_stopped, const std::string& path);
    void running();
    void stopped();
}
