#include "Emu/RSX/VK/vkutils/memory_budget.hpp"

#include <array>
#include <cstdint>
#include <gtest/gtest.h>

namespace
{
	struct heap_budget
	{
		std::uint64_t usage;
		std::uint64_t budget;
	};
}

TEST(VulkanMemoryBudget, EmptyHeapDoesNotHideLaterMemoryPressure)
{
	const std::array<heap_budget, 4> heaps{{{20, 100}, {0, 0}, {90, 100}, {0, 0}}};
	EXPECT_FLOAT_EQ(vk::maximum_budget_usage(heaps), 90.f);
}

TEST(VulkanMemoryBudget, NoBudgetsReportsZero)
{
	const std::array<heap_budget, 2> heaps{{{0, 0}, {0, 0}}};
	EXPECT_FLOAT_EQ(vk::maximum_budget_usage(heaps), 0.f);
}
