#include <gtest/gtest.h>
#include <filesystem>
#include <fstream>
#include <chrono>

#define private public
#include "Emu/Cell/lv2/sys_fs.h"
#undef private

using namespace utils;

extern bool savedata_commit_by_copy(const std::string& from, const std::string& to);

namespace
{
	class savedata_copy_test : public testing::Test
	{
	protected:
		std::filesystem::path root = std::filesystem::temp_directory_path() /
			("rpcs3-save-copy-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));

		void SetUp() override { std::filesystem::create_directories(root / "working"); }
		void TearDown() override { std::filesystem::remove_all(root); }
	};

	TEST_F(savedata_copy_test, CopiesCompleteSaveAndRemovesStaleDestinationFiles)
	{
		const std::string payload(150000, 'S');
		std::filesystem::create_directories(root / "save");
		std::ofstream(root / "working" / "DATA", std::ios::binary) << payload;
		std::ofstream(root / "save" / "OLD", std::ios::binary) << "stale";
		ASSERT_TRUE(savedata_commit_by_copy((root / "working").string(), (root / "save").string()));
		std::ifstream input(root / "save" / "DATA", std::ios::binary);
		EXPECT_EQ(std::string((std::istreambuf_iterator<char>(input)), {}), payload);
		EXPECT_FALSE(std::filesystem::exists(root / "save" / "OLD"));
		EXPECT_TRUE(std::filesystem::exists(root / "working" / "DATA"));
	}

	TEST_F(savedata_copy_test, KeepsWorkingDataWhenCopyCannotFinish)
	{
		std::ofstream(root / "working" / "DATA", std::ios::binary) << "latest";
		std::filesystem::create_directories(root / "working" / "unexpected-directory");
		EXPECT_FALSE(savedata_commit_by_copy((root / "working").string(), (root / "save").string()));
		EXPECT_TRUE(std::filesystem::exists(root / "working" / "DATA"));
		EXPECT_FALSE(savedata_commit_by_copy((root / "missing").string(), (root / "save").string()));
	}
}

namespace utils
{
	TEST(cellFs, PathRoot)
	{
		std::string path = "/.";
		auto [root, trail] = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_TRUE(root.empty());
		EXPECT_TRUE(trail.empty());

		path = "/./././dev_bdvd/./";
		std::tie(root, trail) = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_EQ(root, "dev_bdvd"sv);
		EXPECT_TRUE(trail.empty());

		path = "/../";
		std::tie(root, trail) = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_TRUE(root.empty());
		EXPECT_EQ(trail, "ENOENT"sv);
	}

	TEST(cellFs, PathSimplify)
	{
		std::string path = "/dev_hdd0/";
		auto [root, trail] = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_EQ(root, "dev_hdd0"sv);
		EXPECT_TRUE(trail.empty());

		path = "/dev_hdd0/game";
		std::tie(root, trail) = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_EQ(root, "dev_hdd0"sv);
		EXPECT_EQ(trail, "game"sv);

		path = "/dev_hdd0/game/NP1234567";
		std::tie(root, trail) = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_EQ(root, "dev_hdd0"sv);
		EXPECT_EQ(trail, "game/NP1234567"sv);

		path = "/dev_hdd0/game/NP1234567/../../NP1234568/.";
		std::tie(root, trail) = lv2_fs_object::get_path_root_and_trail(path);
		EXPECT_EQ(root, "dev_hdd0"sv);
		EXPECT_EQ(trail, "NP1234568"sv);
	}
}
