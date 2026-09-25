#pragma once
#if defined(__ANDROID__)
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include "OpenGLESCompat.h"
#elif !defined(_WIN32)
#include <GL/glew.h>
#endif

#ifdef _WIN32
#include <Windows.h>
#include "GL/gl.h"
#include <glext.h>
typedef BOOL (WINAPI* PFNWGLSWAPINTERVALEXTPROC) (int interval);

#define OPENGL_PROC(p, n) extern p gl##n
#define WGL_PROC(p, n) extern p wgl##n
#define OPENGL_PROC2(p, n, tn) OPENGL_PROC(p, n)
	#include "GLProcTable.h"
#undef OPENGL_PROC
#undef WGL_PROC
#undef OPENGL_PROC2

#elif defined(__ANDROID__)
// GLES declarations are included above. Android has no desktop GL/gl.h.

#else
#include <GL/gl.h>
#ifdef HAVE_X11
#include <GL/glxew.h>
#include <GL/glx.h>
#include <GL/glxext.h>
#endif
#endif

#ifndef GL_TEXTURE_BUFFER_BINDING
//During spec release, this enum was removed during upgrade from ARB equivalent
//See https://www.khronos.org/bugzilla/show_bug.cgi?id=844
#define GL_TEXTURE_BUFFER_BINDING 0x8C2A
#endif

namespace gl
{
	void init();
	void set_swapinterval(int interval);

#ifdef __ANDROID__
	namespace es
	{
		// Android owns the native window. The returned context object owns only
		// EGL resources and may therefore be destroyed from the RSX/compiler
		// thread that last used it.
		void* create_context(void* native_window, void* share_context);
		void destroy_context(void* context);
		void make_current(void* context, void* native_window);
		void swap_buffers();
	}
#endif
}
