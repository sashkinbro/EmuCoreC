#include "stdafx.h"
#include "overlay_manager.h"
#include "overlay_perf_metrics.h"
#include "Emu/RSX/RSXThread.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/Cell/PPUThread.h"
#include "Emu/system_config.h"

#include <algorithm>
#include <cmath>
#include <utility>

#include "util/cpu_stats.hpp"
#include "Utilities/StrUtil.h"

#ifdef HAVE_VULKAN
#include "Emu/RSX/VK/vkutils/device.h"
#endif

// OpenGL is available on desktop and through the Android EGL/GLES backend.
// Apple builds do not include the GL renderer.
#if !defined(__APPLE__)
#define EMUCOREC_HAS_GL_RENDERER 1
#include "Emu/RSX/GL/OpenGL.h"
#endif

#include "rpcs3_version.h"

namespace rsx
{
	namespace overlays
	{
		namespace
		{
			// Identity strings for the overlay header, supplied by the host app
			// (EmuCoreC Android app via JNI). Empty on desktop.
			std::string g_app_version;
			std::string g_app_build;
			std::string g_device_name;

			bool g_gpu_name_queried = false;
			video_renderer g_gpu_name_renderer = video_renderer::null;
			std::string g_gpu_name;

			// Overlay palette: parameter names light blue, values white.
			// Text is near-opaque (0.97 alpha) with a black glow shadow.
			const color4f label_color{ 0x9D / 255.f, 0xD7 / 255.f, 0xFF / 255.f, 1.f };
			const color4f value_color{ 1.f, 1.f, 1.f, 1.f };

			// Fake the soft black shadow with a few hard offsets.
			constexpr std::array<f32, 3> shadow_steps{ 1.f, 2.f, 3.f };

			void push_line(std::vector<perf_text_line>& lines, const std::string& label_text, const std::string& value_text, f32 opacity)
			{
				perf_text_line line;

				if (!label_text.empty())
				{
					line.runs.push_back({ label_text, color4f{ label_color.r, label_color.g, label_color.b, opacity } });
				}

				if (!value_text.empty())
				{
					line.runs.push_back({ value_text, color4f{ value_color.r, value_color.g, value_color.b, opacity } });
				}

				lines.push_back(std::move(line));
			}

			std::string get_gpu_name()
			{
				const video_renderer renderer = g_cfg.video.renderer;
				if (g_gpu_name_queried && g_gpu_name_renderer == renderer)
				{
					return g_gpu_name;
				}

				g_gpu_name_renderer = renderer;

				std::string name;

				switch (renderer)
				{
				case video_renderer::vulkan:
				{
#ifdef HAVE_VULKAN
					if (const auto* device = vk::g_render_device)
					{
						name = device->gpu().get_name();
					}
#endif
					break;
				}
				case video_renderer::opengl:
				{
#ifdef EMUCOREC_HAS_GL_RENDERER
					if (const auto* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER)))
					{
						name = renderer;
					}
#endif
					break;
				}
				default:
					break;
				}

				// Compact vendor noise from GPU names.
				if (!name.empty())
				{
					name = fmt::replace_all(name, "Qualcomm ", "");
					name = fmt::replace_all(name, "(TM)", "");
					name = fmt::replace_all(name, "(R)", "");

					// Collapse consecutive whitespace.
					std::string compact;
					compact.reserve(name.size());
					bool in_space = false;
					for (const char c : name)
					{
						if (c == ' ' || c == '\t')
						{
							in_space = true;
							continue;
						}
						if (in_space && !compact.empty())
						{
							compact += ' ';
						}
						in_space = false;
						compact += c;
					}
					name = std::move(compact);
				}

				// The overlay can be initialized just before the GL context or Vulkan
				// device becomes available. Cache only a real result so a later update
				// can populate the device name instead of keeping an empty value forever.
				g_gpu_name_queried = !name.empty();
				g_gpu_name = std::move(name);
				return g_gpu_name;
			}
		}

		void set_app_info(std::string version, std::string build, std::string device)
		{
			g_app_version = std::move(version);
			g_app_build = std::move(build);
			g_device_name = std::move(device);
		}

		void perf_metrics_overlay::reset_transform(label& elm) const
		{
			// left, top, right, bottom
			const areau padding { m_padding, m_padding - std::min<u32>(4, m_padding), m_padding, m_padding };
			positionu pos;

			u16 graph_width = 0;
			u16 graph_height = 0;

			if (m_framerate_graph_enabled)
			{
				graph_width = std::max(graph_width, m_fps_graph.w);
				graph_height += m_fps_graph.get_height();
			}

			if (m_frametime_graph_enabled)
			{
				graph_width = std::max(graph_width, m_frametime_graph.w);
				graph_height += m_frametime_graph.get_height();
			}

			if (graph_height > 0 && m_body.h > 0)
			{
				graph_height += m_padding;
			}

			const u16 overlay_width = std::max(m_body.w, graph_width);
			const u16 overlay_height = static_cast<u16>(m_body.h + graph_height);
			const auto percent_to_margin_px = [](f32 margin_percent, u16 virtual_size, u16 overlay_size) -> u32
			{
				if (overlay_size >= virtual_size)
				{
					return 0;
				}

				const u32 max_margin = virtual_size - overlay_size;
				const u32 margin_px = static_cast<u32>(std::lround((std::clamp(margin_percent, 0.0f, 100.0f) / 100.0f) * max_margin));
				return std::min(margin_px, max_margin);
			};

			const positionu margin
			{
				percent_to_margin_px(m_margin_x, m_virtual_width, overlay_width),
				percent_to_margin_px(m_margin_y, m_virtual_height, overlay_height)
			};

			switch (m_quadrant)
			{
			case screen_quadrant::top_left:
				pos.x = margin.x;
				pos.y = margin.y;
				break;
			case screen_quadrant::top_right:
				pos.x = m_virtual_width - overlay_width - margin.x;
				pos.y = margin.y;
				break;
			case screen_quadrant::bottom_left:
				pos.x = margin.x;
				pos.y = m_virtual_height - overlay_height - margin.y;
				break;
			case screen_quadrant::bottom_right:
				pos.x = m_virtual_width - overlay_width - margin.x;
				pos.y = m_virtual_height - overlay_height - margin.y;
				break;
			}

			if (m_center_x)
			{
				pos.x = overlay_width >= m_virtual_width ? 0 : (m_virtual_width - overlay_width) / 2;
			}

			if (m_center_y)
			{
				pos.y = overlay_height >= m_virtual_height ? 0 : (m_virtual_height - overlay_height) / 2;
			}

			elm.set_pos(pos.x, pos.y);
			elm.set_padding(padding.x1, padding.x2, padding.y1, padding.y2);
		}

		void perf_metrics_overlay::reset_transforms()
		{
			const u16 fps_graph_h = 60;
			const u16 frametime_graph_h = 45;

			if (m_framerate_graph_enabled)
			{
				m_fps_graph.set_size(m_fps_graph.w, fps_graph_h);
			}

			if (m_frametime_graph_enabled)
			{
				m_frametime_graph.set_size(m_frametime_graph.w, frametime_graph_h);
			}

			// Set body/titles transform
			if (m_force_repaint)
			{
				reset_body();
			}
			else
			{
				reset_transform(m_body);
			}

			if (m_framerate_graph_enabled || m_frametime_graph_enabled)
			{
				// Position the graphs within the body
				const u16 graphs_width = m_body.w;
				const u16 body_left = m_body.x;
				s16 y_offset = m_body.y;

				if (m_body.h > 0)
				{
					y_offset += static_cast<s16>(m_body.h + m_padding);
				}

				if (m_framerate_graph_enabled)
				{
					if (m_force_repaint)
					{
						m_fps_graph.set_font_size(static_cast<u16>(m_font_size * 0.8));
					}
					m_fps_graph.update();
					m_fps_graph.set_pos(body_left, y_offset);
					m_fps_graph.set_size(graphs_width, fps_graph_h);

					y_offset += m_fps_graph.get_height();
				}

				if (m_frametime_graph_enabled)
				{
					if (m_force_repaint)
					{
						m_frametime_graph.set_font_size(static_cast<u16>(m_font_size * 0.8));
					}
					m_frametime_graph.update();
					m_frametime_graph.set_pos(body_left, y_offset);
					m_frametime_graph.set_size(graphs_width, frametime_graph_h);
				}
			}

			m_force_repaint = false;
		}

		void perf_metrics_overlay::reset_body()
		{
			m_body.set_font(m_font.c_str(), m_font_size);
			// EmuCoreC: text is rendered manually with per-run colors; the label
			// is transparent and only serves as a layout anchor.
			m_body.fore_color = color4f(1.f, 1.f, 1.f, m_opacity);
			m_body.back_color = color4f(0.f, 0.f, 0.f, 0.f);
			reset_transform(m_body);
		}

		void perf_metrics_overlay::init()
		{
			m_padding = m_font_size / 2;
			m_fps_graph.set_one_percent_sort_high(false);
			m_frametime_graph.set_one_percent_sort_high(true);

			reset_transforms();
			force_next_update();

			if (!m_is_initialised)
			{
				m_update_timer.Start();
				m_frametime_timer.Start();
			}

			update(get_system_time());

			// The text might have changed during the update. Recalculate positions.
			reset_transforms();

			m_is_initialised = true;
			visible = true;
		}

		void perf_metrics_overlay::build_body_lines()
		{
			m_body_lines.clear();

			if (m_show_header && m_detail != detail_level::none)
			{
				// Identity: app version, build, core version, CPU (SoC), GPU.
				// Versions on top, device rows at the bottom.
				const std::string version_tail = rpcs3::get_version().to_string(true);

				std::string app_tail;
				if (!g_app_version.empty())
				{
					fmt::append(app_tail, "-%s", g_app_version);
					if (!g_app_build.empty())
					{
						fmt::append(app_tail, " | %s", g_app_build);
					}
					fmt::append(app_tail, " | %s", version_tail);
				}
				else
				{
					fmt::append(app_tail, " | %s", version_tail);
				}

				push_line(m_body_lines, "EmuCoreC", app_tail, m_opacity);
			}

			switch (m_detail)
			{
			case detail_level::none:
			{
				break;
			}
			case detail_level::minimal:
			{
				push_line(m_body_lines, "FPS:", fmt::format(" %.2f", m_fps), m_opacity);
				break;
			}
			case detail_level::low:
			{
				push_line(m_body_lines, "FPS:", fmt::format(" %.2f", m_fps), m_opacity);
				push_line(m_body_lines, "CPU:", fmt::format(" %.1f %%", m_cpu_usage), m_opacity);
				break;
			}
			case detail_level::medium:
			{
				push_line(m_body_lines, "FPS:", fmt::format(" %.2f", m_fps), m_opacity);
				push_line(m_body_lines, "PPU:", fmt::format(" %.1f %%", m_ppu_usage), m_opacity);
				push_line(m_body_lines, "SPU:", fmt::format(" %.1f %%", m_spu_usage), m_opacity);
				push_line(m_body_lines, "RSX:", fmt::format(" %.1f %%", m_rsx_usage), m_opacity);
				push_line(m_body_lines, "Total:", fmt::format(" %.1f %%", m_cpu_usage), m_opacity);
				break;
			}
			case detail_level::high:
			{
				push_line(m_body_lines, "FPS:", fmt::format(" %.2f (%.1fms)", m_fps, m_frametime), m_opacity);
				push_line(m_body_lines, "PPU:", fmt::format(" %.1f %% (%u)", m_ppu_usage, m_ppus), m_opacity);
				push_line(m_body_lines, "SPU:", fmt::format(" %.1f %% (%u)", m_spu_usage, m_spus), m_opacity);
				push_line(m_body_lines, "RSX:", fmt::format(" %.1f %% (1)", m_rsx_usage), m_opacity);
				push_line(m_body_lines, "RSX Load:", fmt::format(" %u %%", m_rsx_load), m_opacity);
				push_line(m_body_lines, "Total:", fmt::format(" %.1f %% (%u)", m_cpu_usage, m_total_threads), m_opacity);
				break;
			}
			}

			if (m_show_header && m_detail != detail_level::none)
			{
				// Device rows at the bottom: CPU first, then GPU.
				// The GPU row also carries the renderer and internal resolution.
				if (!g_device_name.empty())
				{
					push_line(m_body_lines, "CPU:", fmt::format(" %s", g_device_name), m_opacity);
				}

				const std::string gpu_name = get_gpu_name();
				std::string renderer_name = "Null";
				switch (g_cfg.video.renderer)
				{
				case video_renderer::vulkan: renderer_name = "Vulkan"; break;
				case video_renderer::opengl: renderer_name = "OpenGL"; break;
				default: break;
				}

				u32 res_w = 0;
				u32 res_h = 0;
				if (auto* avconfig = g_fxo->try_get<rsx::avconf>())
				{
					res_w = avconfig->resolution_x;
					res_h = avconfig->resolution_y;

					const u16 scale = g_cfg.video.resolution_scale_percent;
					if (scale != 100)
					{
						res_w = res_w * scale / 100;
						res_h = res_h * scale / 100;
					}
				}

				std::string gpu_value;
				if (!gpu_name.empty())
				{
					fmt::append(gpu_value, " %s |", gpu_name);
				}
				fmt::append(gpu_value, " %s", renderer_name);
				if (res_w > 0 && res_h > 0)
				{
					fmt::append(gpu_value, " | %ux%u", res_w, res_h);
				}

				// Keep the active backend visible even if a driver temporarily cannot
				// provide GL_RENDERER (for example while the context is being rebuilt).
				push_line(m_body_lines, "GPU:", gpu_value, m_opacity);
			}
		}

		void perf_metrics_overlay::measure_body_lines(u16& width, u16& height) const
		{
			width = 0;
			height = 0;

			if (m_body_lines.empty())
			{
				return;
			}

			font* f = m_body.get_font();
			const f32 size_px = f->get_size_px();

			u32 line_count = 0;

			for (const auto& line : m_body_lines)
			{
				f32 line_width = 0.f;

				for (const auto& run : line.runs)
				{
					if (run.text.empty())
					{
						continue;
					}

					const std::u32string str = utf8_to_u32string(run.text);
					const auto verts = f->render_text(str.c_str());
					if (!verts.empty())
					{
						line_width += verts.back().values[0];
					}
				}

				width = std::max<u16>(width, static_cast<u16>(std::ceil(line_width)));
				line_count++;
			}

			height = static_cast<u16>(size_px + (line_count - 1) * (size_px + 2.f));
		}

		void perf_metrics_overlay::render_body(compiled_resource& out) const
		{
			if (m_body_lines.empty())
			{
				return;
			}

			font* f = m_body.get_font();
			const f32 size_px = f->get_size_px();
			const f32 line_height = size_px + 2.f;

			const u16 pad_left = m_padding;
			const u16 pad_top = m_padding - std::min<u32>(4, m_padding);
			const u16 pad_right = m_padding;
			const u16 text_region_w = m_body.w > pad_left + pad_right ? m_body.w - pad_left - pad_right : 0;

			const f32 base_x = m_body.x + pad_left;
			const f32 base_y = m_body.y + pad_top + size_px;

			const bool right_aligned = !m_center_x && (m_quadrant == screen_quadrant::top_right || m_quadrant == screen_quadrant::bottom_right);

			compiled_resource text_res;
			std::vector<vertex> shadow_verts;

			f32 y = base_y;

			for (const auto& line : m_body_lines)
			{
				// Render each run once, measure it from the produced vertices.
				struct rendered_run
				{
					std::vector<vertex> verts;
					f32 width;
					color4f color;
				};

				std::vector<rendered_run> runs;
				f32 line_width = 0.f;

				for (const auto& run : line.runs)
				{
					if (run.text.empty())
					{
						continue;
					}

					const std::u32string str = utf8_to_u32string(run.text);
					auto verts = f->render_text(str.c_str());
					const f32 width = verts.empty() ? 0.f : verts.back().x();
					line_width += width;
					runs.push_back({ std::move(verts), width, run.color });
				}

				if (runs.empty())
				{
					y += line_height;
					continue;
				}

				f32 line_x = base_x;
				if (m_center_x)
				{
					line_x += (text_region_w - line_width) * 0.5f;
				}
				else if (right_aligned)
				{
					line_x += text_region_w - line_width;
				}

				f32 x = line_x;

				for (auto& run : runs)
				{
					if (!run.verts.empty())
					{
						for (auto& v : run.verts)
						{
							v.x() += x;
							v.y() += y;
						}

						auto& cmd = text_res.append({});
						cmd.config.set_font(f);
						cmd.config.color = run.color;
						cmd.verts = std::move(run.verts);

						// Black glow shadow underneath the text.
						for (const f32 step : shadow_steps)
						{
							for (const auto& v : cmd.verts)
							{
								vertex sv = v;
								sv.x() += step;
								sv.y() += step;
								shadow_verts.push_back(sv);
							}
						}
					}

					x += run.width;
				}

				y += line_height;
			}

			if (!shadow_verts.empty())
			{
				auto& shadow_cmd = out.append({});
				shadow_cmd.config.set_font(f);
				shadow_cmd.config.color = color4f(0.f, 0.f, 0.f, 0.45f);
				shadow_cmd.verts = std::move(shadow_verts);
			}

			out.add(text_res);
		}

		void perf_metrics_overlay::set_framerate_graph_enabled(bool enabled)
		{
			if (m_framerate_graph_enabled == enabled)
				return;

			m_framerate_graph_enabled = enabled;

			if (enabled)
			{
				m_fps_graph.set_title("Framerate: 00.0");
				m_fps_graph.set_font_size(static_cast<u16>(m_font_size * 0.8));
				m_fps_graph.set_color(color4f(1.f, 1.f, 1.f, m_opacity));
				m_fps_graph.set_guide_interval(10);
			}

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_frametime_graph_enabled(bool enabled)
		{
			if (m_frametime_graph_enabled == enabled)
				return;

			m_frametime_graph_enabled = enabled;

			if (enabled)
			{
				m_frametime_graph.set_title("Frametime: 0.0");
				m_frametime_graph.set_font_size(static_cast<u16>(m_font_size * 0.8));
				m_frametime_graph.set_color(color4f(1.f, 1.f, 1.f, m_opacity));
				m_frametime_graph.set_guide_interval(8);
			}

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_framerate_datapoint_count(u32 datapoint_count)
		{
			if (m_fps_graph.get_datapoint_count() == datapoint_count)
				return;

			m_fps_graph.set_count(datapoint_count);
			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_frametime_datapoint_count(u32 datapoint_count)
		{
			if (m_frametime_graph.get_datapoint_count() == datapoint_count)
				return;

			m_frametime_graph.set_count(datapoint_count);
			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_graph_detail_levels(perf_graph_detail_level framerate_level, perf_graph_detail_level frametime_level)
		{
			m_fps_graph.set_labels_visible(
				framerate_level == perf_graph_detail_level::show_all || framerate_level == perf_graph_detail_level::show_min_max,
				framerate_level == perf_graph_detail_level::show_all || framerate_level == perf_graph_detail_level::show_one_percent_avg);
			m_frametime_graph.set_labels_visible(
				frametime_level == perf_graph_detail_level::show_all || frametime_level == perf_graph_detail_level::show_min_max,
				frametime_level == perf_graph_detail_level::show_all || frametime_level == perf_graph_detail_level::show_one_percent_avg);

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_detail_level(detail_level level)
		{
			if (m_detail == level)
				return;

			m_detail = level;

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_position(screen_quadrant quadrant)
		{
			if (m_quadrant == quadrant)
				return;

			m_quadrant = quadrant;

			m_force_repaint = true;
		}

		// In ms
		void perf_metrics_overlay::set_update_interval(u32 update_interval)
		{
			m_update_interval = update_interval;
		}

		void perf_metrics_overlay::set_font(std::string font)
		{
			if (m_font == font)
				return;

			m_font = std::move(font);

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_font_size(u16 font_size)
		{
			if (m_font_size == font_size)
				return;

			m_font_size = font_size;
			m_padding = m_font_size / 2;

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_margins(f32 margin_x, f32 margin_y, bool center_x, bool center_y)
		{
			if (m_margin_x == margin_x && m_margin_y == margin_y && m_center_x == center_x && m_center_y == center_y)
				return;

			m_margin_x = margin_x;
			m_margin_y = margin_y;
			m_center_x = center_x;
			m_center_y = center_y;

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_opacity(f32 opacity)
		{
			if (m_opacity == opacity)
				return;

			m_opacity = opacity;

			m_force_repaint = true;
		}

		void perf_metrics_overlay::set_show_header(bool enabled)
		{
			if (m_show_header == enabled)
				return;

			m_show_header = enabled;

			m_force_repaint = true;
		}

		void perf_metrics_overlay::force_next_update()
		{
			m_force_update = true;
		}

		void perf_metrics_overlay::set_render_viewport(u16 width, u16 height)
		{
			u16 new_virtual_width = virtual_width;
			u16 new_virtual_height = virtual_height;

			if (use_window_space && width > 0 && height > 0)
			{
				const double scale_x = static_cast<double>(width) / virtual_width;
				const double scale_y = static_cast<double>(height) / virtual_height;
				const double scale = std::min(scale_x, scale_y);

				new_virtual_width = static_cast<u16>(std::min<u32>(
					static_cast<u32>(std::lround(width / scale)),
					std::numeric_limits<u16>::max()));

				new_virtual_height = static_cast<u16>(std::min<u32>(
					static_cast<u32>(std::lround(height / scale)),
					std::numeric_limits<u16>::max()));
			}

			if (m_virtual_width == new_virtual_width && m_virtual_height == new_virtual_height)
				return;

			m_virtual_width = new_virtual_width;
			m_virtual_height = new_virtual_height;

			if (m_is_initialised)
			{
				reset_transforms();
			}
		}

		void perf_metrics_overlay::update(u64 /*timestamp_us*/)
		{
			const auto elapsed_update = m_update_timer.GetElapsedTimeInMilliSec();
			const bool do_update = m_force_update || elapsed_update >= m_update_interval;

			if (m_is_initialised)
			{
				if (m_frametime_graph_enabled && !m_force_update)
				{
					const float elapsed_frame = static_cast<float>(m_frametime_timer.GetElapsedTimeInMilliSec());
					m_frametime_timer.Start();
					m_frametime_graph.record_datapoint(elapsed_frame, do_update);
					m_frametime_graph.set_title(fmt::format("Frametime: %4.1f", elapsed_frame).c_str());
				}

				if (m_force_repaint)
				{
					reset_transforms();
				}
			}

			if (!m_force_update)
			{
				++m_frames;
			}

			if (do_update)
			{
				// 1. Fetch/calculate metrics we'll need
				if (!m_is_initialised || !m_force_update)
				{
					m_update_timer.Start();

					auto& rsx_thread = g_fxo->get<rsx::thread>();

					switch (m_detail)
					{
					case detail_level::high:
					{
						m_frametime = std::max(0.f, static_cast<float>(elapsed_update / m_frames));

						m_rsx_load = rsx_thread.get_load();

						m_total_threads = utils::cpu_stats::get_current_thread_count();

						[[fallthrough]];
					}
					case detail_level::medium:
					{
						m_ppus = idm::select<named_thread<ppu_thread>>([this](u32, named_thread<ppu_thread>& ppu)
						{
							m_ppu_cycles += thread_ctrl::get_cycles(ppu);
						});

						m_spus = idm::select<named_thread<spu_thread>>([this](u32, named_thread<spu_thread>& spu)
						{
							m_spu_cycles += thread_ctrl::get_cycles(spu);
						});

						m_rsx_cycles += rsx_thread.get_cycles();

						m_total_cycles = std::max<u64>(1, m_ppu_cycles + m_spu_cycles + m_rsx_cycles);
						m_cpu_usage    = static_cast<f32>(m_cpu_stats.get_usage());

						m_ppu_usage = std::clamp(m_cpu_usage * m_ppu_cycles / m_total_cycles, 0.f, 100.f);
						m_spu_usage = std::clamp(m_cpu_usage * m_spu_cycles / m_total_cycles, 0.f, 100.f);
						m_rsx_usage = std::clamp(m_cpu_usage * m_rsx_cycles / m_total_cycles, 0.f, 100.f);

						[[fallthrough]];
					}
					case detail_level::low:
					{
						if (m_detail == detail_level::low) // otherwise already acquired in medium
							m_cpu_usage = static_cast<f32>(m_cpu_stats.get_usage());

						[[fallthrough]];
					}
					case detail_level::minimal:
					{
						[[fallthrough]];
					}
					case detail_level::none:
					{
						m_fps = std::max(0.f, static_cast<f32>(m_frames / (elapsed_update / 1000)));
						if (m_is_initialised && m_framerate_graph_enabled)
						{
							m_fps_graph.record_datapoint(m_fps, true);
							m_fps_graph.set_title(fmt::format("Framerate: %04.1f", m_fps).c_str());
						}
						break;
					}
					}
				}

				// 2. Build the overlay text (colored runs) and size the body to it.
				build_body_lines();

				u16 text_w = 0;
				u16 text_h = 0;
				measure_body_lines(text_w, text_h);

				const u16 body_w = text_w == 0 ? 0 : static_cast<u16>(text_w + m_padding * 2);
				const u16 body_h = text_h == 0 ? 0 : static_cast<u16>(text_h + (m_padding - std::min<u32>(4, m_padding)) + m_padding);

				if (m_body.w != body_w || m_body.h != body_h)
				{
					m_body.set_size(body_w, body_h);
					reset_transforms();
				}

				if (!m_force_update)
				{
					m_frames = 0;
				}
				else
				{
					// Only force once
					m_force_update = false;
				}

				if (m_framerate_graph_enabled)
				{
					m_fps_graph.update();
				}

				if (m_frametime_graph_enabled)
				{
					m_frametime_graph.update();
				}
			}
		}

		compiled_resource perf_metrics_overlay::get_compiled()
		{
			if (!visible)
			{
				return {};
			}

			compiled_resource compiled_resources;

			render_body(compiled_resources);

			if (m_framerate_graph_enabled)
			{
				compiled_resources.add(m_fps_graph.get_compiled());
			}

			if (m_frametime_graph_enabled)
			{
				compiled_resources.add(m_frametime_graph.get_compiled());
			}

			return compiled_resources;
		}

		graph::graph()
		{
			m_label.set_font("e046323ms.ttf", 8);
			m_label.alignment = text_align::center;
			m_label.fore_color = { 1.f, 1.f, 1.f, 1.f };
			m_label.back_color = { 0.f, 0.f, 0.f, .7f };

			back_color = { 0.f, 0.f, 0.f, 0.5f };
		}

		void graph::set_pos(s16 _x, s16 _y)
		{
			m_label.set_pos(_x, _y);
			overlay_element::set_pos(_x, _y + m_label.h);
		}

		void graph::set_size(u16 _w, u16 _h)
		{
			m_label.set_size(_w, m_label.h);
			overlay_element::set_size(_w, _h);
		}

		void graph::set_title(const char* title)
		{
			m_title = title;
		}

		void graph::set_font(const char* font_name, u16 font_size)
		{
			m_label.set_font(font_name, font_size);
		}

		void graph::set_font_size(u16 font_size)
		{
			const auto font_name = m_label.get_font()->get_name().data();
			m_label.set_font(font_name, font_size);
		}

		void graph::set_count(u32 datapoint_count)
		{
			m_datapoint_count = datapoint_count;

			if (m_datapoints.empty())
			{
				m_datapoints.resize(m_datapoint_count, -1.0f);
			}
			else if (m_datapoints.empty() || m_datapoint_count < m_datapoints.size())
			{
				std::copy(m_datapoints.begin() + m_datapoints.size() - m_datapoint_count, m_datapoints.end(), m_datapoints.begin());
				m_datapoints.resize(m_datapoint_count);
			}
			else
			{
				m_datapoints.insert(m_datapoints.begin(), m_datapoint_count - m_datapoints.size(), -1.0f);
			}
		}

		void graph::set_color(color4f color)
		{
			m_color = color;
		}

		void graph::set_guide_interval(f32 guide_interval)
		{
			m_guide_interval = guide_interval;
		}

		void graph::set_labels_visible(bool show_min_max, bool show_1p_avg)
		{
			m_show_min_max = show_min_max;
			m_show_1p_avg = show_1p_avg;
		}

		void graph::set_one_percent_sort_high(bool sort_1p_high)
		{
			m_1p_sort_high = sort_1p_high;
		}

		u16 graph::get_height() const
		{
			return h + m_label.h + m_label.padding_top + m_label.padding_bottom;
		}

		u32 graph::get_datapoint_count() const
		{
			return m_datapoint_count;
		}

		void graph::record_datapoint(f32 datapoint, bool update_metrics)
		{
			ensure(datapoint >= 0.0f);

			// std::dequeue is only faster for large sizes, so just use a std::vector and resize once in while

			// Record datapoint
			m_datapoints.push_back(datapoint);

			// Cull vector when it gets large
			if (m_datapoints.size() > m_datapoint_count * 16ull)
			{
				std::copy(m_datapoints.begin() + m_datapoints.size() - m_datapoint_count, m_datapoints.end(), m_datapoints.begin());
				m_datapoints.resize(m_datapoint_count);
			}

			if (!update_metrics)
			{
				return;
			}

			m_min = max_v<f32>;
			m_max = 0.0f;
			m_avg = 0.0f;
			m_1p = 0.0f;

			std::vector<f32> valid_datapoints;

			// Make sure min/max reflects the data being displayed, not the entire datapoints vector
			for (usz i = m_datapoints.size() - m_datapoint_count; i < m_datapoints.size(); i++)
			{
				const f32 dp = m_datapoints[i];

				if (dp < 0) continue; // Skip initial negative values. They don't count.

				m_min = std::min(m_min, dp);
				m_max = std::max(m_max, dp);
				m_avg += dp;

				if (m_show_1p_avg)
				{
					valid_datapoints.push_back(dp);
				}
			}

			// Sanitize min value
			m_min = std::min(m_min, m_max);

			if (m_show_1p_avg && !valid_datapoints.empty())
			{
				// Sort datapoints (we are only interested in the lowest/highest 1%)
				const usz i_1p = valid_datapoints.size() / 100;
				const usz n_1p = i_1p + 1;

				if (m_1p_sort_high)
					std::nth_element(valid_datapoints.begin(), valid_datapoints.begin() + i_1p, valid_datapoints.end(), std::greater<f32>());
				else
					std::nth_element(valid_datapoints.begin(), valid_datapoints.begin() + i_1p, valid_datapoints.end());

				// Calculate statistics
				m_avg /= valid_datapoints.size();
				m_1p = std::accumulate(valid_datapoints.begin(), valid_datapoints.begin() + n_1p, 0.0f) / static_cast<float>(n_1p);
			}
		}

		void graph::update()
		{
			std::string fps_info = m_title;

			if (m_show_1p_avg)
			{
				fmt::append(fps_info, "\n1%%:%4.1f av:%4.1f", m_1p, m_avg);
			}

			if (m_show_min_max)
			{
				fmt::append(fps_info, "\nmn:%4.1f mx:%4.1f", m_min, m_max);
			}

			m_label.set_text(fps_info);
			m_label.set_padding(4, 4, 0, 4);

			m_label.auto_resize();
			m_label.refresh();

			// If label horizontal end is larger, widen graph width to match it
			set_size(std::max(m_label.w, w), h);
		}

		compiled_resource& graph::get_compiled()
		{
			if (is_compiled())
			{
				return compiled_resources;
			}

			overlay_element::get_compiled();

			const f32 normalize_factor = f32(h) / (m_max != 0.0f ? m_max : 1.0f);

			// Don't show guide lines if they'd be more dense than 1 guide line every 3 pixels
			const bool guides_too_dense = (m_max / m_guide_interval) > (h / 3.0f);

			if (m_guide_interval > 0 && !guides_too_dense)
			{
				auto& cmd_guides = compiled_resources.append({});
				auto& config_guides = cmd_guides.config;

				config_guides.color = { 1.f, 1.f, 1.f, .2f };
				config_guides.primitives = primitive_type::line_list;

				auto& verts_guides = compiled_resources.draw_commands.back().verts;

				for (auto y_off = m_guide_interval; y_off < m_max; y_off += m_guide_interval)
				{
					const f32 guide_y = y + h - y_off * normalize_factor;
					verts_guides.emplace_back(x, guide_y);
					verts_guides.emplace_back(static_cast<float>(x + w), guide_y);
				}
			}

			auto& cmd_graph = compiled_resources.append({});
			auto& config_graph = cmd_graph.config;

			config_graph.color = m_color;
			config_graph.primitives = primitive_type::line_strip;

			auto& verts_graph = compiled_resources.draw_commands.back().verts;

			f32 x_stride = w;
			if (m_datapoint_count > 2)
			{
				x_stride /= (m_datapoint_count - 1);
			}

			const usz tail_index_offset = m_datapoints.size() - m_datapoint_count;

			for (u32 i = 0; i < m_datapoint_count; ++i)
			{
				const f32 x_line = x + i * x_stride;
				const f32 y_line = y + h - (std::max(0.0f, m_datapoints[i + tail_index_offset]) * normalize_factor);
				verts_graph.emplace_back(x_line, y_line);
			}

			compiled_resources.add(m_label.get_compiled());

			return compiled_resources;
		}

		extern void reset_performance_overlay()
		{
			if (!g_cfg.misc.use_native_interface)
				return;

			if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>())
			{
				auto& perf_settings = g_cfg.video.perf_overlay;
				auto perf_overlay = manager->get<rsx::overlays::perf_metrics_overlay>();

				if (perf_settings.enabled)
				{
					if (!perf_overlay)
					{
						perf_overlay = manager->create<rsx::overlays::perf_metrics_overlay>();
					}

					std::lock_guard lock(*manager);

					perf_overlay->set_detail_level(perf_settings.level);
					perf_overlay->set_position(perf_settings.position);
					perf_overlay->set_update_interval(perf_settings.update_interval);
					perf_overlay->set_font(perf_settings.font);
					perf_overlay->set_font_size(perf_settings.font_size);
					perf_overlay->set_margins(static_cast<f32>(perf_settings.margin_x.get()), static_cast<f32>(perf_settings.margin_y.get()), perf_settings.center_x.get(), perf_settings.center_y.get());
					perf_overlay->use_window_space = perf_settings.use_window_space.get();
					perf_overlay->set_opacity(perf_settings.opacity / 100.f);
					perf_overlay->set_show_header(perf_settings.show_header.get());
					perf_overlay->set_framerate_datapoint_count(perf_settings.framerate_datapoint_count);
					perf_overlay->set_frametime_datapoint_count(perf_settings.frametime_datapoint_count);
					perf_overlay->set_framerate_graph_enabled(perf_settings.framerate_graph_enabled.get());
					perf_overlay->set_frametime_graph_enabled(perf_settings.frametime_graph_enabled.get());
					perf_overlay->set_graph_detail_levels(perf_settings.framerate_graph_detail_level.get(), perf_settings.frametime_graph_detail_level.get());
					perf_overlay->init();
				}
				else if (perf_overlay)
				{
					manager->remove<rsx::overlays::perf_metrics_overlay>();
				}
			}
		}
	} // namespace overlays
} // namespace rsx
