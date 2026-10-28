#include "stdafx.h"
#include "OboeCapture.h"
#include <oboe/Oboe.h>
#include <mutex>

LOG_CHANNEL(mic_log, "Microphone");

namespace
{
	std::mutex g_devices_mutex;
	std::vector<std::pair<std::string, s32>> g_devices;
}

oboe_capture::~oboe_capture() { close(); }

void oboe_capture::set_devices(std::vector<std::pair<std::string, s32>> devices)
{
	std::lock_guard lock(g_devices_mutex);
	g_devices = std::move(devices);
}

bool oboe_capture::open(const std::string& name, u32 rate, u32 channels)
{
	close();
	s32 id = oboe::kUnspecified;
	{
		std::lock_guard lock(g_devices_mutex);
		for (const auto& [device_name, device_id] : g_devices)
		{
			if (!name.empty() && name != "Default" && device_name == name) { id = device_id; break; }
		}
	}
	if (!name.empty() && name != "Default" && id == oboe::kUnspecified)
		mic_log.warning("Microphone '%s' is unavailable; using Android's default input", name);
	for (const auto preset : {oboe::InputPreset::VoicePerformance, oboe::InputPreset::Generic})
	{
		oboe::AudioStreamBuilder builder;
		builder.setDirection(oboe::Direction::Input)->setDeviceId(id)->setSharingMode(oboe::SharingMode::Shared)
			->setPerformanceMode(oboe::PerformanceMode::None)->setInputPreset(preset)->setFormat(oboe::AudioFormat::I16)
			->setChannelCount(static_cast<s32>(channels))->setSampleRate(static_cast<s32>(rate))
			->setBufferCapacityInFrames(static_cast<s32>(rate / 5))->setFormatConversionAllowed(true)
			->setChannelConversionAllowed(true)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
		const auto result = builder.openStream(m_stream);
		if (result == oboe::Result::OK)
		{
			m_name = name;
			m_read_failed = false;
			return true;
		}
		mic_log.warning("Cannot open microphone '%s': %s", name, oboe::convertToText(result));
		m_stream.reset();
	}
	return false;
}

void oboe_capture::close()
{
	if (m_stream) { m_stream->close(); m_stream.reset(); }
}

bool oboe_capture::start()
{
	if (!m_stream) return false;
	const auto result = m_stream->requestStart();
	if (result != oboe::Result::OK) mic_log.error("Cannot start microphone '%s': %s", m_name, oboe::convertToText(result));
	return result == oboe::Result::OK;
}

void oboe_capture::stop() { if (m_stream) m_stream->requestStop(); }

u32 oboe_capture::read(void* output, u32 frames)
{
	if (!m_stream || !frames) return 0;
	const auto result = m_stream->read(output, static_cast<s32>(frames), 0);
	if (!result)
	{
		if (!std::exchange(m_read_failed, true)) mic_log.error("Microphone '%s' stopped: %s", m_name, oboe::convertToText(result.error()));
		return 0;
	}
	return static_cast<u32>(result.value());
}
