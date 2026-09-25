#include "stdafx.h"
#include "program.h"
#include "state_tracker.hpp"

#include "Emu/system_config.h"
#include "util/cctype.hpp"

namespace gl
{
	namespace glsl
	{
#ifdef RSX_GLES
		static void convert_to_gles(std::string& source)
		{
			auto replace_all = [&source](std::string_view needle, std::string_view replacement)
			{
				for (std::size_t pos = 0; (pos = source.find(needle, pos)) != std::string::npos; pos += replacement.size())
				{
					source.replace(pos, needle.size(), replacement);
				}
			};

			const auto version_start = source.find("#version ");
			if (version_start == std::string::npos)
			{
				rsx_log.error("GLES shader is missing a #version header");
				return;
			}
			if (version_start)
			{
				// Embedded raw GLSL snippets intentionally start on the line after R"(.
				source.erase(0, version_start);
			}

			const auto version_end = source.find('\n');
			source.replace(0, version_end, "#version 320 es");

			auto erase_line = [&source](std::string_view needle)
			{
				for (;;)
				{
					const auto pos = source.find(needle);
					if (pos == std::string::npos)
					{
						break;
					}
					const auto end = source.find('\n', pos);
					source.erase(pos, end == std::string::npos ? source.size() - pos : end - pos + 1);
				}
			};
			erase_line("#extension GL_ARB_shader_texture_image_samples");
			erase_line("#extension GL_ARB_separate_shader_objects");
			erase_line("#extension GL_ARB_shader_stencil_export");

			// Extension directives must precede precision declarations in ESSL.
			const auto header_end = source.find('\n');
			source.insert(header_end + 1, "#extension GL_EXT_clip_cull_distance : require\n");
			std::size_t declarations_at = header_end + 1;
			while (source.compare(declarations_at, 11, "#extension ") == 0)
			{
				const auto line_end = source.find('\n', declarations_at);
				declarations_at = line_end == std::string::npos ? source.size() : line_end + 1;
			}
			source.insert(declarations_at,
				"#define RSX_GLES 1\n"
				"#ifndef USE_UBO\n"
				"#define USE_UBO 0\n"
				"#endif\n"
				"precision highp float;\n"
				"precision highp int;\n"
				"precision highp sampler2D;\n"
				"precision highp sampler2DArray;\n"
				"precision highp sampler3D;\n"
				"precision highp samplerCube;\n"
				"precision highp sampler2DMS;\n"
				"precision highp isampler2D;\n"
				"precision highp isampler2DArray;\n"
				"precision highp usampler2D;\n"
				"precision highp usampler2DArray;\n"
				"precision highp usamplerBuffer;\n"
				"precision highp image2D;\n"
				"precision highp iimage2D;\n"
				"precision highp uimage2D;\n");

			// RSX 1D resources are backed by one-row 2D GLES textures. Texture
			// operation macros supply the synthetic Y coordinate.
			for (std::size_t pos = 0; (pos = source.find("sampler1D", pos)) != std::string::npos; pos += 9)
			{
				source.replace(pos, 9, "sampler2D");
			}

			// Adreno's ESSL compiler rejects arithmetic expressions in sampler
			// binding layout qualifiers even though they are compile-time constants.
			// RPCS3's temporary image slots are defined as 31 - index.
			for (u32 index = 0; index < 32; ++index)
			{
				const auto sampler_binding = fmt::format("%u", 31 - index);
				replace_all(fmt::format("SAMPLER_BINDING(%u)", index), sampler_binding);

				// The generated helper programs use arithmetic macros in layout
				// qualifiers. Resolve the currently configured slot bases before
				// compiling; Qualcomm ESSL requires a literal binding value.
				replace_all(fmt::format("IMAGE_LOCATION(%u)", index), fmt::format("%u", index));
				replace_all(fmt::format("SSBO_LOCATION(%u)", index), fmt::format("%u", index + 2));
				replace_all(fmt::format("UBO_LOCATION(%u)", index), fmt::format("%u", index + 8));

				// Some mobile preprocessors do not expand token-pasting through a
				// second function-like macro (TEX_NAME(n) -> tex##n).
				replace_all(fmt::format("TEX_NAME_STENCIL(%u)", index), fmt::format("tex%u_stencil", index));
				replace_all(fmt::format("TEX_NAME(%u)", index), fmt::format("tex%u", index));
				replace_all(fmt::format("TEX1D(%u,", index), fmt::format("TEX1D_%u(", index));
				replace_all(fmt::format("TEX2D(%u,", index), fmt::format("TEX2D_%u(", index));
			}

			// ESSL only permits const locals with compile-time initializers, while
			// upstream desktop GLSL also uses const for values derived at runtime.
			for (std::size_t pos = 0; (pos = source.find("const ", pos)) != std::string::npos;)
			{
				source.erase(pos, 6);
			}
		}
#endif

		void patch_macros_INTEL(std::string& source)
		{
			auto read_token = [&source](size_t start) -> std::tuple<size_t, size_t>
			{
				size_t string_begin = std::string::npos, i = start;
				for (size_t count = 0; i < source.length(); ++i)
				{
					const char c = source[i];
					const auto is_space = utils::isspace(c);

					if (string_begin == std::string::npos)
					{
						if (c == '\n') break;
						if (is_space) continue;

						string_begin = i;
					}

					if (is_space)
					{
						if (!count) break;
					}
					else if (c == '(')
					{
						count++;
					}
					else if (c == ')')
					{
						count--;
					}
				}

				return std::make_tuple(string_begin, i - 1);
			};

			auto is_exempt = [&source](const std::string_view& token) -> bool
			{
				const char* handled_keywords[] =
				{
					"SSBO_LOCATION(x)",
					"UBO_LOCATION(x)",
					"IMAGE_LOCATION(x)"
				};

				for (const auto& keyword : handled_keywords)
				{
					if (token.starts_with(keyword))
					{
						return false;
					}
				}

				return true;
			};

			size_t prev_loc = 0;
			while (true)
			{
				// Find macro define blocks and remove the outer-most brackets around the expression part
				const auto next_loc = source.find("#define", prev_loc);
				if (next_loc == std::string::npos)
				{
					break;
				}

				prev_loc = next_loc + 1;

				const auto [name_start, name_end] = read_token(next_loc + ("#define"sv).length());
				if (name_start == std::string::npos)
				{
					break;
				}

				const auto macro_name = std::string_view(source.data() + name_start, (name_end - name_start) + 1);
				if (is_exempt(macro_name))
				{
					continue;
				}

				const auto [expr_start, expr_end] = read_token(name_end + 1);
				if (expr_start == std::string::npos)
				{
					continue;
				}

				if (source[expr_start] == '(' && source[expr_end] == ')')
				{
					rsx_log.notice("[Compiler warning] We'll remove brackets around the expression named '%s'. Add it to exclusion list if this is not desired.", macro_name);

					source[expr_start] = ' ';
					source[expr_end] = ' ';
				}
			}
		}

		void shader::precompile()
		{
#ifdef RSX_GLES
			convert_to_gles(source);
#endif

			if (gl::get_driver_caps().vendor_INTEL)
			{
				// Workaround for broken macro expansion.
				patch_macros_INTEL(source);
			}

			const char* str = source.c_str();
			const GLint length = ::narrow<GLint>(source.length());

			if (g_cfg.video.log_programs)
			{
				std::string base_name;
				switch (type)
				{
				case ::glsl::program_domain::glsl_vertex_program:
					base_name = "shaderlog/VertexProgram";
					break;
				case ::glsl::program_domain::glsl_fragment_program:
					base_name = "shaderlog/FragmentProgram";
					break;
				case ::glsl::program_domain::glsl_compute_program:
					base_name = "shaderlog/ComputeProgram";
					break;
				default:
					fmt::throw_exception("Unexpected program type %d", static_cast<int>(type));
				}

				fs::write_file(fs::get_cache_dir() + base_name + std::to_string(m_id) + ".glsl", fs::rewrite, str, length);
			}

			glShaderSource(m_id, 1, &str, &length);

			m_init_fence.create();
			flush_command_queue(m_init_fence);
		}

		void shader::create(::glsl::program_domain type_, const std::string & src)
		{
			type = type_;
			source = src;

			GLenum shader_type{};
			switch (type)
			{
			case ::glsl::program_domain::glsl_vertex_program:
				shader_type = GL_VERTEX_SHADER;
				break;
			case ::glsl::program_domain::glsl_fragment_program:
				shader_type = GL_FRAGMENT_SHADER;
				break;
			case ::glsl::program_domain::glsl_compute_program:
				shader_type = GL_COMPUTE_SHADER;
				break;
			default:
				rsx_log.fatal("gl::glsl::shader::compile(): Unhandled shader type (%d)", +type_);
				return;
			}

			m_id = glCreateShader(shader_type);
			precompile();
		}

		shader& shader::compile()
		{
			std::lock_guard lock(m_compile_lock);
			if (m_is_compiled)
			{
				// Another thread compiled this already
				return *this;
			}

			ensure(!m_init_fence.is_empty()); // Do not attempt to compile a shader_view!!
			m_init_fence.server_wait_sync();

			glCompileShader(m_id);

			GLint status = GL_FALSE;
			glGetShaderiv(m_id, GL_COMPILE_STATUS, &status);

			if (status == GL_FALSE)
			{
				GLint length = 0;
				glGetShaderiv(m_id, GL_INFO_LOG_LENGTH, &length);

				std::string error_msg;
				if (length)
				{
					std::unique_ptr<GLchar[]> buf(new char[length + 1]);
					glGetShaderInfoLog(m_id, length, nullptr, buf.get());
					error_msg = buf.get();
				}

				rsx_log.fatal("Compilation failed: %s\nsource: %s", error_msg, source);
			}

			m_compiled_fence.create();
			flush_command_queue(m_compiled_fence);

			m_is_compiled = true;
			return *this;
		}

		bool program::uniforms_t::has_location(const std::string & name, int* location)
		{
			auto found = locations.find(name);
			if (found != locations.end())
			{
				if (location)
				{
					*location = found->second;
				}

				return (found->second >= 0);
			}

			auto result = glGetUniformLocation(m_program->id(), name.c_str());
			locations[name] = result;

			if (location)
			{
				*location = result;
			}

			return (result >= 0);
		}

		GLint program::uniforms_t::location(const std::string& name)
		{
			auto found = locations.find(name);
			if (found != locations.end())
			{
				if (found->second >= 0)
				{
					return found->second;
				}
				else
				{
					rsx_log.fatal("%s not found.", name);
					return -1;
				}
			}

			auto result = glGetUniformLocation(m_program->id(), name.c_str());

			if (result < 0)
			{
				rsx_log.fatal("%s not found.", name);
				return result;
			}

			locations[name] = result;
			return result;
		}

		void program::link(std::function<void(program*)> init_func)
		{
			// Keep the link diagnostic attributable to this call. Optional desktop
			// compatibility entry points may leave a benign error on GLES.
			while (glGetError() != GL_NO_ERROR)
			{
			}
			glLinkProgram(m_id);
			const GLenum link_error = glGetError();

			GLint status = GL_FALSE;
			glGetProgramiv(m_id, GL_LINK_STATUS, &status);

			if (status == GL_FALSE)
			{
				GLint length = 0;
				glGetProgramiv(m_id, GL_INFO_LOG_LENGTH, &length);

				std::string error_msg;
				if (length)
				{
					std::unique_ptr<GLchar[]> buf(new char[length + 1]);
					glGetProgramInfoLog(m_id, length, nullptr, buf.get());
					error_msg = buf.get();
				}

				GLint attached_shaders = 0;
				glGetProgramiv(m_id, GL_ATTACHED_SHADERS, &attached_shaders);
				rsx_log.fatal("Linkage failed (program=%u, attached=%d, gl_error=0x%x): %s", m_id, attached_shaders, link_error, error_msg);
			}
			else
			{
				if (init_func)
				{
					init_func(this);
				}

				m_fence.create();
				flush_command_queue(m_fence);
			}
		}

		void program::validate()
		{
			glValidateProgram(m_id);

			GLint status = GL_FALSE;
			glGetProgramiv(m_id, GL_VALIDATE_STATUS, &status);

			if (status == GL_FALSE)
			{
				GLint length = 0;
				glGetProgramiv(m_id, GL_INFO_LOG_LENGTH, &length);

				std::string error_msg;
				if (length)
				{
					std::unique_ptr<GLchar[]> buf(new char[length + 1]);
					glGetProgramInfoLog(m_id, length, nullptr, buf.get());
					error_msg = buf.get();
				}

				rsx_log.error("Validation failed: %s", error_msg.c_str());
			}
		}
	}
}
