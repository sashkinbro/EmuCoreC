#pragma once

namespace vk
{
	template <typename Budgets>
	float maximum_budget_usage(const Budgets& budgets)
	{
		float max_usage = 0.f;
		for (const auto& info : budgets)
		{
			if (!info.budget) continue;
			const float usage = (info.usage * 100.f) / info.budget;
			if (usage > max_usage) max_usage = usage;
		}
		return max_usage;
	}
}
