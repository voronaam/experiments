#!/usr/bin/env bash
# Builds the tree-sitter core library that the Java edition loads through the tree-sitter-ng binding
# (io.github.bonede:tree-sitter), for every platform the binding ships natives for, and writes it to
#   src/main/resources/native/tree-sitter/lib/<target>-tree-sitter.<so|dylib|dll>
# from where dev.codecity.analysis.NativeLibraries extracts and loads it at start-up.
#
# Why not the library in the binding's jar: it is compiled without optimisation (zig c++ -g0, no -O) and its JNI glue
# looks up the TSNode class and six field ids for every node it hands to Java. Built with -O2 and with the lookups
# cached (tree-sitter-jni-cache.patch), analysis is 3-5x faster with an identical city.
#
# The library is the binding's JNI glue (org_treesitter_TSParser.c + ConvertUTF.c, from the tree-sitter-ng sources of
# the binding version in pom.xml) linked with the tree-sitter runtime of the version the Rust and D editions use. The
# runtime's C API only grew between the two versions, so the glue compiles unchanged against it, and the grammars
# (separate libraries from the grammar jars) are loaded as before: they only pass their TSLanguage to the runtime.
#
# Sources are pinned by version and SHA-256 and downloaded once into native/.build (git-ignored). Zig is the C cross
# compiler, so one Linux machine builds all five targets. Re-running produces byte-identical libraries, except for the
# link timestamps in the Windows DLL. Usage:
#   native/build-tree-sitter.sh            (ZIG=/path/to/zig to use a zig that is not on the PATH; tested with 0.13.0)
set -euo pipefail

TREE_SITTER_VERSION=0.27.0
TREE_SITTER_SHA256=d35c96e68736bd9569d2757c3cc71052485f33082c3825f1aed9d0e86013a159
BINDING_VERSION=0.26.6 # must match tree-sitter.version in pom.xml: the glue implements that jar's native methods
BINDING_SHA256=84a581a8bbe74e0873955d6485995434f239fedaa93285e788aa1a7ccbeb918e
# The binding's targets (treeSitterTargets in its gradle.properties), named as its NativeUtils looks them up. Linux
# builds are linked against glibc 2.17 so that they run on any distribution of the last decade.
TARGETS=(x86_64-linux-gnu aarch64-linux-gnu x86_64-macos aarch64-macos x86_64-windows)

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build="$here/.build"
out="$here/../src/main/resources/native/tree-sitter/lib"
zig="${ZIG:-zig}"
mkdir -p "$build" "$out"

fetch() { # name url sha256
  # Downloads and verifies a release tarball once, then unpacks it into "<name>". The ".tmp" directory is renamed only
  # when complete, so an interrupted run never leaves a half-filled source tree behind.
  local dir="$build/$1" tgz="$build/$1.tar.gz"
  [ -d "$dir" ] && return
  echo "fetching $1"
  curl -fsSL -o "$tgz" "$2"
  echo "$3  $tgz" | sha256sum -c --quiet -
  rm -rf "$dir.tmp"; mkdir -p "$dir.tmp"
  tar xzf "$tgz" -C "$dir.tmp" --strip-components=1
  mv "$dir.tmp" "$dir"
}
fetch "tree-sitter-$TREE_SITTER_VERSION" \
  "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v$TREE_SITTER_VERSION.tar.gz" "$TREE_SITTER_SHA256"
fetch "tree-sitter-ng-$BINDING_VERSION" \
  "https://github.com/bonede/tree-sitter-ng/archive/refs/tags/v$BINDING_VERSION.tar.gz" "$BINDING_SHA256"

# The patched glue goes to its own directory, so the unpacked sources stay pristine and re-runs re-apply the patch.
ts="$build/tree-sitter-$TREE_SITTER_VERSION/lib"
ng="$build/tree-sitter-ng-$BINDING_VERSION"
glue="$build/glue"
rm -rf "$glue"; mkdir -p "$glue"
cp "$ng"/tree-sitter/src/main/c/*.c "$ng"/tree-sitter/src/main/c/*.h "$glue/"
patch -s -d "$glue" -p5 < "$here/tree-sitter-jni-cache.patch"

for target in "${TARGETS[@]}"; do
  case "$target" in
    *-linux-gnu) ext=so;    jni=linux;  zt="$target.2.17" ;;
    *-macos)     ext=dylib; jni=darwin; zt="$target" ;;
    *-windows)   ext=dll;   jni=win32;  zt="$target" ;;
  esac
  lib="$out/$target-tree-sitter.$ext"
  echo "building $(basename "$lib")"
  # -O2 as in the D edition's build. -fvisibility=hidden exports only the JNI entry points (JNIEXPORT), which lets the
  # compiler and linker drop or inline the rest; -s strips symbols and debug info. lib.c is tree-sitter's single-file
  # amalgamation of the runtime. -w silences warnings in the third-party sources.
  "$zig" cc -target "$zt" -shared -O2 -std=gnu11 -fvisibility=hidden -s -w \
    -I "$ts/include" -I "$ts/src" -I "$ng/include/jni" -I "$ng/include/jni/$jni" \
    -o "$lib" "$ts/src/lib.c" "$glue/org_treesitter_TSParser.c" "$glue/ConvertUTF.c"
done
# zig leaves import libraries and debug databases next to a Windows DLL; only the DLL is needed.
rm -f "$out"/*.lib "$out"/*.pdb
ls -l "$out"
