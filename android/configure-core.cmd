@echo off
rem EmuCoreC: configure the RPCS3 Android core build on Windows.
rem
rem Gradle's Exec environment does not survive into the nested cmake
rem FetchContent processes (absl via protobuf runs `git submodule update`,
rem whose bash script needs the Git for Windows bin dirs and exec path), so
rem the environment is fixed here in a cmd wrapper instead.
rem
rem Usage: configure-core.cmd <cmake.exe> <ninja.exe> <ndk-dir> <build-dir> [extra -D args...]
setlocal
set "MINGW=%LOCALAPPDATA%\mingw\mingw64\bin"
set "GITBINS=C:\Program Files\Git\cmd;C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;C:\Program Files\Git\mingw64\bin"
set "PATH=%MINGW%;%GITBINS%;%PATH%"
set "GIT_EXEC_PATH=C:\Program Files\Git\mingw64\libexec\git-core"

rem Resolve a working Python 3 interpreter; the install path varies between
rem machines and Python upgrades.
set "PYTHON_EXE="
for %%C in (
  "%LOCALAPPDATA%\Programs\Python\Python314\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
  "C:\Python314\python.exe"
  "C:\Python313\python.exe"
  "C:\Python312\python.exe"
) do (
  if not defined PYTHON_EXE if exist "%%~C" set "PYTHON_EXE=%%~C"
)
if not defined PYTHON_EXE (
  for /f "delims=" %%P in ('where python.exe 2^>nul') do (
    if not defined PYTHON_EXE set "PYTHON_EXE=%%P"
  )
)
if not defined PYTHON_EXE (
  for /f "delims=" %%P in ('py -3 -c "import sys;print(sys.executable)" 2^>nul') do (
    set "PYTHON_EXE=%%P"
  )
)
if not defined PYTHON_EXE (
  echo [configure-core] Python 3 interpreter not found. 1>&2
  exit /b 1
)
echo [configure-core] Using Python: %PYTHON_EXE%

set "CMAKE_EXE=%~1"
set "NINJA_EXE=%~2"
set "NDK_DIR=%~3"
set "BUILD_DIR=%~4"
shift /4

rem %~dp0.. is the repository root; the android/ subdirectory is added to the
rem root CMake project behind if(ANDROID).
"%CMAKE_EXE%" -S "%~dp0.." -B "%BUILD_DIR%" -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK_DIR%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a ^
  -DANDROID_PLATFORM=android-29 ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
  -DUSE_NATIVE_INSTRUCTIONS=OFF ^
  -DUSE_SDL=OFF ^
  -DUSE_SYSTEM_SDL=OFF ^
  -DUSE_GAMEMODE=OFF ^
  -DUSE_SYSTEM_LIBUSB=OFF ^
  -DUSE_SYSTEM_CURL=OFF ^
  -DUSE_SYSTEM_OPENCV=OFF ^
  -DUSE_SYSTEM_FFMPEG=OFF ^
  -DUSE_SYSTEM_ZLIB=ON ^
  -DUSE_DISCORD_RPC=OFF ^
  -DUSE_FAUDIO=OFF ^
  -DUSE_LIBEVDEV=OFF ^
  -DWITH_LLVM=ON ^
  -DBUILD_LLVM=ON ^
  -DSTATIC_LINK_LLVM=ON ^
  -DLLVM_ENABLE_LIBCXX=ON ^
  -DUSE_LTO=OFF ^
  -DASMJIT_NO_SHM_OPEN=ON ^
  -DPython3_EXECUTABLE="%PYTHON_EXE%" ^
  -DLLVM_HOST_TRIPLE=aarch64-linux-android ^
  "-DCROSS_TOOLCHAIN_FLAGS_NATIVE=-DCMAKE_C_COMPILER=%MINGW%\gcc.exe;-DCMAKE_CXX_COMPILER=%MINGW%\g++.exe" ^
  %*
exit /b %ERRORLEVEL%
