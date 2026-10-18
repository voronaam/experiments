#!/usr/bin/env bash
# Fetches pinned sources of the native libraries CodeCity-D links against and builds them into static libraries:
#   third_party/build/libcodecity_parsers.a  tree-sitter + Java/TypeScript/TSX/Rust grammars (always)
#   third_party/build/libcodecity_gui.a      GLFW 3.4 (X11) + Dear ImGui 1.92.9 via cimgui (desktop window)
# Re-running is cheap: sources are downloaded once and libraries are rebuilt only when missing.
#
# Environment: NATIVE_CC / NATIVE_CXX (default cc / c++), AR (default ar), X11_INCLUDE (extra header dir for GLFW),
#              CODECITY_NO_GUI=1 to skip the GUI libraries.
#
# dub runs this as a pre-build command (see dub.json); the `cli` and `unittest` configurations set CODECITY_NO_GUI=1.
# A library is rebuilt only when its .a file is missing, so delete third_party/build to force a rebuild.
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

parsers="$build/libcodecity_parsers.a"
if [ ! -f "$parsers" ]; then
  echo "Building tree-sitter parsers…"
  fetch tree-sitter            https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v0.27.0.tar.gz
  fetch tree-sitter-java       https://github.com/tree-sitter/tree-sitter-java/archive/refs/tags/v0.23.5.tar.gz
  fetch tree-sitter-typescript https://github.com/tree-sitter/tree-sitter-typescript/archive/refs/tags/v0.23.2.tar.gz
  fetch tree-sitter-rust       https://github.com/tree-sitter/tree-sitter-rust/archive/refs/tags/v0.24.2.tar.gz
  obj="$build/obj-parsers"; mkdir -p "$obj"
  ts="$third/tree-sitter/lib"
  # -w silences warnings in the third-party sources; -fPIC allows linking into a position-independent executable.
  c() { "$cc" -O2 -std=gnu11 -fPIC -w "$@"; }
  # lib.c is tree-sitter's single-file amalgamation of the runtime.
  c -I"$ts/include" -I"$ts/src" -c "$ts/src/lib.c" -o "$obj/tree_sitter.o"
  c -I"$third/tree-sitter-java/src" -c "$third/tree-sitter-java/src/parser.c" -o "$obj/java_parser.o"
  # The TypeScript repository holds two grammars (TypeScript and TSX), each with a generated parser and a
  # hand-written external scanner; the Rust grammar has a scanner too, Java has none.
  for g in typescript tsx; do
    d="$third/tree-sitter-typescript/$g/src"
    c -I"$d" -c "$d/parser.c" -o "$obj/${g}_parser.o"
    c -I"$d" -c "$d/scanner.c" -o "$obj/${g}_scanner.o"
  done
  d="$third/tree-sitter-rust/src"
  c -I"$d" -c "$d/parser.c" -o "$obj/rust_parser.o"
  c -I"$d" -c "$d/scanner.c" -o "$obj/rust_scanner.o"
  rm -f "$parsers"; $ar rcs "$parsers" "$obj"/*.o
fi

gui="$build/libcodecity_gui.a"
if [ "${CODECITY_NO_GUI:-0}" != "1" ] && [ ! -f "$gui" ]; then
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
if [ "${CODECITY_NO_GUI:-0}" != "1" ] && [ ! -f "$fonts/JetBrainsMono-Regular.ttf" ]; then
  echo "Fetching fonts (Inter, JetBrains Mono — SIL Open Font License)…"
  mkdir -p "$fonts"
  curl -fsSL -o "$fonts/Inter.ttf" https://raw.githubusercontent.com/rsms/inter/v4.1/docs/font-files/InterVariable.ttf
  curl -fsSL -o "$fonts/Inter-LICENSE.txt" https://raw.githubusercontent.com/rsms/inter/v4.1/LICENSE.txt
  curl -fsSL -o "$fonts/JetBrainsMono-OFL.txt" https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/OFL.txt
  curl -fsSL -o "$fonts/JetBrainsMono-Regular.ttf" https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-Regular.ttf
fi
echo "Native libraries ready in $build"
