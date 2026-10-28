#include <gtest/gtest.h>

#include "Emu/Io/pad_config.h"

TEST(pad_config, saved_handler_survives_serialization_and_restores_bindings)
{
	cfg_input original;
	auto& player = original.player1;
	ASSERT_TRUE(player.handler.from_string("Keyboard"));
	ASSERT_TRUE(player.device.from_string("My Keyboard"));
	ASSERT_TRUE(player.config.cross.from_string("Space"));
	player.backup_config("Keyboard");
	ASSERT_TRUE(player.handler.from_string("Null"));
	player.config.from_default();

	cfg_input loaded;
	ASSERT_TRUE(loaded.from_string(original.to_string()));
	ASSERT_TRUE(loaded.player1.restore_config(pad_handler::keyboard));
	EXPECT_EQ(loaded.player1.device.to_string(), "My Keyboard");
	EXPECT_EQ(loaded.player1.config.cross.to_string(), "Space");
	EXPECT_EQ(loaded.player1.saved_configs.find(pad_handler::keyboard), nullptr);
}

TEST(pad_config, untouched_handler_is_not_saved)
{
	cfg_input config;
	ASSERT_TRUE(config.player1.handler.from_string("Keyboard"));
	config.player1.backup_config(config.player1.device.to_string());
	EXPECT_EQ(config.player1.saved_configs.find(pad_handler::keyboard), nullptr);
	EXPECT_EQ(config.to_string().find("Saved Configurations"), std::string::npos);
}
