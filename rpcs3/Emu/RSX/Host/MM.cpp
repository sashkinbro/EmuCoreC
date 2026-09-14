#include "stdafx.h"
#include "MM.h"
#include <Emu/RSX/Common/simple_array.hpp>
#include <Emu/RSX/RSXOffload.h>
#include <Emu/RSX/Utils/rsx_utils.h>

#include <Emu/Memory/vm.h>
#include <Emu/IdManager.h>
#include <Emu/system_config.h>
#include <Utilities/address_range.h>
#include <Utilities/mutex.h>

#ifdef __ANDROID__
#include <array>
#include <atomic>
#endif

namespace rsx
{
	rsx::simple_array<MM_block> g_deferred_mprotect_queue;
	shared_mutex g_mprotect_queue_lock;

#ifdef __ANDROID__
	// RSX protections are independent of vm::page_* flags. Keep a compact
	// per-guest-page mirror so Android code can avoid entering a heavyweight
	// texture-cache flush from its small signal stack. One byte per 4 KiB page
	// covers the complete 32-bit guest address space.
	static constexpr usz s_guest_page_count = 0x1'0000'0000ull / 4096;
	std::array<std::atomic<u8>, s_guest_page_count> g_guest_page_protection{};

	static void mm_track_protection(u64 start, u64 length, utils::protection prot)
	{
		if (!length)
		{
			return;
		}

		const u64 guest_base = reinterpret_cast<u64>(vm::base(0));
		if (start < guest_base || start >= guest_base + 0x1'0000'0000ull)
		{
			return;
		}

		const u64 first = (start - guest_base) / 4096;
		const u64 end = std::min<u64>(start - guest_base + length, 0x1'0000'0000ull);
		const u64 last = (end - 1) / 4096;
		const u8 value = static_cast<u8>(prot);
		for (u64 page = first; page <= last; page++)
		{
			g_guest_page_protection[page].store(value, std::memory_order_release);
		}
	}

	bool mm_is_accessible(u32 vm_address, bool is_writing)
	{
		const auto prot = static_cast<utils::protection>(
			g_guest_page_protection[vm_address / 4096].load(std::memory_order_acquire));
		return is_writing ? prot == utils::protection::rw : prot != utils::protection::no;
	}
#else
	static void mm_track_protection(u64, u64, utils::protection)
	{
	}

	bool mm_is_accessible(u32, bool)
	{
		return true;
	}
#endif

	void mm_sanitize_queue_internal()
	{
		u32 w = 0, r = 0;
		for (; r < g_deferred_mprotect_queue.size(); ++r)
		{
			auto& block = g_deferred_mprotect_queue[r];
			if (!block.range.valid())
			{
				continue;
			}
			g_deferred_mprotect_queue[w++] = block;
		}
		g_deferred_mprotect_queue.resize(w);
	}

	void mm_flush_mprotect_queue_internal(u32 count)
	{
		AUDIT(count <= g_deferred_mprotect_queue.size());

		for (u32 i = 0; i < count; ++i)
		{
			auto& block = g_deferred_mprotect_queue[i];
			ensure(block.range.valid());

			utils::memory_protect(reinterpret_cast<void*>(block.range.start), block.range.length(), block.prot);
			mm_track_protection(block.range.start, block.range.length(), block.prot);
			block.range.invalidate();
		}

		const u32 remaining = g_deferred_mprotect_queue.size() - count;
		if (!remaining)
		{
			g_deferred_mprotect_queue.clear();
			return;
		}

		// Pop processed entries from the queue
		mm_sanitize_queue_internal();
	}

	// Reverse scan to find the latest overlapping MM conflict. The result is a count of prefix blocks.
	template <typename F>
	u32 mm_find_conflict_internal(F&& predicate)
	{
		for (u32 i = g_deferred_mprotect_queue.size(); i > 0; --i)
		{
			if (std::invoke(predicate, g_deferred_mprotect_queue[i - 1]))
			{
				return i;
			}
		}

		return 0;
	}

	void mm_defer_mprotect_internal(u64 start, u64 length, utils::protection prot)
	{
		const auto range = utils::address_range64::start_length(start, length);
		bool has_invalid = false;
		bool is_merged = false;

		// Attempt a merge first. The queue length is short but the time taken to run mprotect is very high in comparison.
		for (auto it = g_deferred_mprotect_queue.rbegin();
			it != g_deferred_mprotect_queue.rend();
			++it)
		{
			auto& block = *it;
			if (!block.touches(range))
			{
				continue;
			}

			if (block.prot != prot)
			{
				// Optimization. If our new range swallows the old one, replace it.
				if (block.range.inside(range))
				{
					block.range.invalidate();
					has_invalid = true;
					continue;
				}

				if (!block.overlaps(range))
				{
					// Adjacent. Skip.
					continue;
				}

				// Preserve ordering. Do not proceed with merge.
				break;
			}

			block.merge(range);
			is_merged = true;
			break;
		}

		if (has_invalid)
		{
			mm_sanitize_queue_internal();
		}

		if (is_merged)
		{
			return;
		}

		g_deferred_mprotect_queue.push_back({ range, prot, rsx::get_shared_tag() });
	}

	void mm_protect(void* ptr, u64 length, utils::protection prot)
	{
		if (g_cfg.video.disable_async_host_memory_manager)
		{
			utils::memory_protect(ptr, length, prot);
			mm_track_protection(reinterpret_cast<u64>(ptr), length, prot);
			return;
		}

		// Naive merge. Eventually it makes more sense to do conflict resolution, but it's not as important.
		const auto start = reinterpret_cast<u64>(ptr);
		const auto range = utils::address_range64::start_length(start, length);

		std::lock_guard lock(g_mprotect_queue_lock);

		if (prot == utils::protection::rw || prot == utils::protection::wx)
		{
			// Basically an unlock op. Flush the conflicting prefix block if any overlap is detected.
			if (u32 count = mm_find_conflict_internal(FN(x.overlaps(range))))
			{
				// Check for degenerate ranges that we'll be crushing
				for (auto pblock = &g_deferred_mprotect_queue[count - 1];
					count > 0 && pblock->range.inside(range);
					count--, pblock--)
				{
					pblock->range.invalidate();
				}

				if (count)
				{
					mm_flush_mprotect_queue_internal(count);
				}
				else
				{
					mm_sanitize_queue_internal();
				}
			}

			utils::memory_protect(ptr, length, prot);
			mm_track_protection(start, length, prot);
			return;
		}

		// No, Ro, etc.
		mm_defer_mprotect_internal(start, length, prot);
		// Expose scheduled restrictive protection immediately. A preflight which
		// sees it will flush the queue through the normal violation handler before
		// touching the memory.
		mm_track_protection(start, length, prot);
	}

	void mm_protect_immediate(void* ptr, u64 length, utils::protection prot)
	{
#ifdef __ANDROID__
		const auto start = reinterpret_cast<u64>(ptr);
		const auto range = utils::address_range64::start_length(start, length);
		std::lock_guard lock(g_mprotect_queue_lock);

		for (const auto& block : g_deferred_mprotect_queue)
		{
			if (block.overlaps(range))
			{
				mm_flush_mprotect_queue_internal(g_deferred_mprotect_queue.size());
				break;
			}
		}

		utils::memory_protect(ptr, length, prot);
		mm_track_protection(start, length, prot);
#else
		utils::memory_protect(ptr, length, prot);
#endif
	}

	void mm_flush()
	{
		std::lock_guard lock(g_mprotect_queue_lock);
		mm_flush_mprotect_queue_internal(g_deferred_mprotect_queue.size());
	}

	void mm_flush(u32 vm_address)
	{
		std::lock_guard lock(g_mprotect_queue_lock);
		if (g_deferred_mprotect_queue.empty())
		{
			return;
		}

		const auto addr = reinterpret_cast<u64>(vm::base(vm_address));
		if (const u32 count = mm_find_conflict_internal(FN(x.overlaps(addr))))
		{
			mm_flush_mprotect_queue_internal(count);
		}
	}

	void mm_flush(const rsx::simple_array<utils::address_range64>& ranges)
	{
		std::lock_guard lock(g_mprotect_queue_lock);
		if (g_deferred_mprotect_queue.empty() || ranges.empty())
		{
			return;
		}

		const auto block_overlaps_ranges = [&](const MM_block& block) { return ranges.any(FN(block.overlaps(x))); };
		if (const u32 count = mm_find_conflict_internal(block_overlaps_ranges))
		{
			mm_flush_mprotect_queue_internal(count);
		}
	}

	void mm_flush_lazy()
	{
		if (!g_cfg.video.multithreaded_rsx)
		{
			mm_flush();
			return;
		}

		std::lock_guard lock(g_mprotect_queue_lock);
		if (g_deferred_mprotect_queue.empty())
		{
			return;
		}

		auto& rsxdma = g_fxo->get<rsx::dma_manager>();
		rsxdma.backend_ctrl(mm_backend_ctrl::cmd_mm_flush, nullptr);
	}

	void mm_flush_partial(u64 last_tag)
	{
		std::lock_guard lock(g_mprotect_queue_lock);

		u32 count = 0;
		for (const auto& block : g_deferred_mprotect_queue)
		{
			if (block.sync_tag > last_tag)
			{
				break;
			}
			count++;
		}

		if (count)
		{
			mm_flush_mprotect_queue_internal(count);
		}
	}
}
