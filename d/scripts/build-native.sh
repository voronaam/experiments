#!/usr/bin/env bash
# Fetches pinned sources of the native libraries the desktop window links against and builds them into a static
# library, plus the fonts it embeds:
#   third_party/build/libcodecity_gui.a  GLFW 3.4 (X11) + Dear ImGui 1.92.9 via cimgui
#   third_party/fonts/                   Inter and JetBrains Mono
# Re-running is cheap: sources are downloaded once and the library is rebuilt only when missing.
#
# Environment: NATIVE_CC / NATIVE_CXX (default cc / c++), AR (default ar), X11_INCLUDE (extra header dir for GLFW).
#
# dub runs this as a pre-build command of the default `gui` configuration only (see dub.json); the `cli` and
# `unittest` configurations are pure D and need no native step. Delete third_party/build to force a rebuild.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
third="$here/third_party"
build="$third/build"
cc="${NATIVE_CC:-cc}"
cxx="${NATIVE_CXX:-c++}"
ar="${AR:-ar}"
mkdir -p "$third" "$build"

fetch() { # name url [strip]
  # Downloads a GitHub release tarball once. It is unpacked into "<name>.tmp" and renamed only when complete, so an
  # interrupted download never leaves a half-filled directory that later runs would mistake for a finished one.
  local dir="$third/$1"
  if [ ! -d "$dir" ]; then
    echo "  fetching $1"
    mkdir -p "$dir.tmp"
    curl -fsSL "$2" | tar xz -C "$dir.tmp" --strip-components=1
    mv "$dir.tmp" "$dir"
  fi
}

gui="$build/libcodecity_gui.a"
if [ ! -f "$gui" ]; then
  echo "Building GLFW and Dear ImGui…"
  fetch glfw   https://github.com/glfw/glfw/archive/refs/tags/3.4.tar.gz
  fetch cimgui https://github.com/cimgui/cimgui/archive/refs/tags/1.92.9.tar.gz
  # cimgui includes Dear ImGui as a git submodule, which its release tarball does not contain: fetch it separately.
  if [ ! -f "$third/cimgui/imgui/imgui.h" ]; then
    rm -rf "$third/cimgui/imgui"; fetch cimgui/imgui https://github.com/ocornut/imgui/archive/refs/tags/v1.92.9.tar.gz
  fi
  obj="$build/obj-gui"; mkdir -p "$obj"
  extra=(); [ -n "${X11_INCLUDE:-}" ] && extra+=("-I$X11_INCLUDE")
  g="$third/glfw/src"
  # GLFW's X11 build: the platform-independent core, the null platform (always compiled since GLFW 3.4), the POSIX
  # helpers, the X11/GLX/EGL/OSMesa backends and Linux joystick support. Wayland is not built.
  for f in context init input monitor platform vulkan window egl_context osmesa_context null_init null_monitor \
           null_window null_joystick posix_module posix_time posix_thread posix_poll x11_init x11_monitor x11_window \
           xkb_unicode glx_context linux_joystick; do
    "$cc" -O2 -std=gnu11 -fPIC -w -D_GLFW_X11 -I"$third/glfw/include" "${extra[@]}" -c "$g/$f.c" -o "$obj/glfw_$f.o"
  done
  im="$third/cimgui/imgui"
  # IMGUI_IMPL_API=extern "C" gives the GLFW and OpenGL 3 backends C linkage, so D can call them directly (cimgui
  # only wraps the core API). GLFW_INCLUDE_NONE stops glfw3.h from including the system OpenGL headers.
  cxxf=(-O2 -std=c++17 -fPIC -w -I"$im" -I"$im/backends" -I"$third/glfw/include" "-DIMGUI_IMPL_API=extern \"C\""
        -DIMGUI_DISABLE_OBSOLETE_FUNCTIONS=1 -DGLFW_INCLUDE_NONE)
  for f in imgui imgui_draw imgui_tables imgui_widgets imgui_demo; do
    "$cxx" "${cxxf[@]}" -c "$im/$f.cpp" -o "$obj/$f.o"
  done
  "$cxx" "${cxxf[@]}" -c "$im/backends/imgui_impl_glfw.cpp" -o "$obj/imgui_impl_glfw.o"
  "$cxx" "${cxxf[@]}" -c "$im/backends/imgui_impl_opengl3.cpp" -o "$obj/imgui_impl_opengl3.o"
  "$cxx" "${cxxf[@]}" -I"$third/cimgui" -c "$third/cimgui/cimgui.cpp" -o "$obj/cimgui.o"
  rm -f "$gui"; $ar rcs "$gui" "$obj"/*.o
fi

fonts="$third/fonts"
if [ ! -f "$fonts/JetBrainsMono-Regular.ttf" ]; then
  echo "Fetching fonts (Inter, JetBrains Mono — SIL Open Font License)…"
  mkdir -p "$fonts"
  curl -fsSL -o "$fonts/Inter.ttf" https://raw.githubusercontent.com/rsms/inter/v4.1/docs/font-files/InterVariable.ttf
  curl -fsSL -o "$fonts/Inter-LICENSE.txt" https://raw.githubusercontent.com/rsms/inter/v4.1/LICENSE.txt
  curl -fsSL -o "$fonts/JetBrainsMono-OFL.txt" https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/OFL.txt
  curl -fsSL -o "$fonts/JetBrainsMono-Regular.ttf" https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-Regular.ttf
fi
echo "Native libraries ready in $build"
