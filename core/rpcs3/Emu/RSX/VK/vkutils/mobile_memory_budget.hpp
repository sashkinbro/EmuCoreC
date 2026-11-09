#pragma once

#include <algorithm>
#include <cassert>
#include <cstdint>

namespace vk
{
	// Aggregate VkDeviceMemory admission on unified-memory devices. The caller serializes
	// reservations with allocation/free, so concurrent heaps cannot spend the same headroom.
	class mobile_memory_budget
	{
		using bytes = std::uint64_t;
		static constexpr bytes quantum = 256ull * 1024 * 1024;
		bytes m_limit;
		bytes m_reserve;
		bytes m_capacity;
		bytes m_used = 0;

		bytes rounded_capacity(bytes value) const
		{
			const bytes remainder = value % quantum;
			const bytes addition = remainder ? quantum - remainder : 0;
			return addition > m_limit - value ? m_limit : value + addition;
		}

	public:
		static bytes limit_for_device(bytes configured_limit, bytes total)
		{
			return std::min(configured_limit, total ? total / 4 : quantum);
		}

		static bytes system_reserve(bytes total)
		{
			return std::clamp<bytes>(total / 8, 512ull << 20, 1536ull << 20);
		}

		mobile_memory_budget(bytes limit, bytes reserve)
			: m_limit(limit), m_reserve(reserve), m_capacity(std::min(limit, quantum)) {}

		bool try_reserve(bytes size, bytes available)
		{
			if (size > m_limit - m_used || available <= m_reserve || size > available - m_reserve)
			{
				return false;
			}

			m_used += size;
			m_capacity = std::max(m_capacity, rounded_capacity(m_used));
			return true;
		}

		void release(bytes size)
		{
			assert(size <= m_used);
			m_used -= size;
			m_capacity = std::max(std::min(m_limit, quantum), rounded_capacity(m_used));
		}

		float pressure(bytes available) const
		{
			if (!m_used) return 0.f;
			const bytes spare = available > m_reserve ? available - m_reserve : 0;
			const bytes budget = m_used + std::min(spare, m_limit - m_used);
			return static_cast<float>(m_used) * 100.f / static_cast<float>(budget);
		}

		bytes used() const { return m_used; }
		bytes capacity() const { return m_capacity; }
		bytes limit() const { return m_limit; }
		bytes reserve() const { return m_reserve; }
	};
}
