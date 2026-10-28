#pragma once

#include "util/types.hpp"
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace oboe { class AudioStream; }

class oboe_capture
{
public:
	~oboe_capture();
	bool open(const std::string& name, u32 rate, u32 channels);
	void close();
	bool start();
	void stop();
	u32 read(void* output, u32 frames);
	static void set_devices(std::vector<std::pair<std::string, s32>> devices);

private:
	std::shared_ptr<oboe::AudioStream> m_stream;
	std::string m_name;
	bool m_read_failed = false;
};
