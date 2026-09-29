#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_NDK_HOME or ANDROID_HOME}/ndk/28.2.13676358}"
API=26

case "$(uname -s)/$(uname -m)" in
    Darwin/*)      HOST_TAG="darwin-x86_64" ;;
    Linux/x86_64)  HOST_TAG="linux-x86_64" ;;
    Linux/aarch64) HOST_TAG="linux-aarch64" ;;
    *)
        echo "unsupported build host: $(uname -s) $(uname -m)" >&2
        exit 1
        ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "NDK toolchain not found: $TOOLCHAIN" >&2
    ls "$NDK/toolchains/llvm/prebuilt" >&2 2>/dev/null || true
    exit 1
fi

PROOT_VERSION=5.1.107.95
PROOT_SHA256=dbb50381c2f0b5c342bdf3d3467d80c21d2a4677d9dadd14159fa3b32f11b319
TALLOC_VERSION=2.4.3
TALLOC_SHA256=dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd

WORK="$(mktemp -d "${TMPDIR:-/tmp}/redt-proot-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d ' ' -f 1
    else
        shasum -a 256 "$1" | cut -d ' ' -f 1
    fi
}

fetch_checked() {
    local url="$1"
    local output="$2"
    local expected="$3"
    curl --fail --location --silent --show-error \
        --connect-timeout 15 --retry 3 "$url" --output "$output"
    local actual
    actual="$(hash_file "$output")"
    if [[ "$actual" != "$expected" ]]; then
        echo "checksum mismatch for $url" >&2
        echo "  expected $expected" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi
}

fetch_checked \
    "https://github.com/termux/proot/archive/v${PROOT_VERSION}.zip" \
    "$WORK/proot.zip" \
    "$PROOT_SHA256"
fetch_checked \
    "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" \
    "$WORK/talloc.tar.gz" \
    "$TALLOC_SHA256"

build_abi() {
    local abi="$1"
    local triple="$2"
    local cc="$TOOLCHAIN/${triple}${API}-clang"
    local ar="$TOOLCHAIN/llvm-ar"
    local strip="$TOOLCHAIN/llvm-strip"
    local abi_work="$WORK/$abi"
    local prefix="$abi_work/prefix"
    local wrappers="$abi_work/tool-wrappers"

    mkdir -p \
        "$abi_work/talloc" \
        "$prefix/lib" \
        "$prefix/include" \
        "$wrappers"
    ln -s "$TOOLCHAIN/llvm-readelf" "$wrappers/readelf"
    unzip -q "$WORK/proot.zip" -d "$abi_work/proot-source"
    tar -xzf "$WORK/talloc.tar.gz" -C "$abi_work/talloc" --strip-components=1
    patch -d "$abi_work/proot-source/proot-${PROOT_VERSION}" -p1 \
        < "$ROOT/tools/proot-ndk28.patch"

    (
        cd "$abi_work/talloc"
        CC="$cc" AR="$ar" RANLIB="$TOOLCHAIN/llvm-ranlib" \
            ./configure \
                --prefix="$prefix" \
                --disable-rpath \
                --disable-python \
                --cross-compile \
                --cross-answers="$ROOT/tools/proot-cross-answers.txt"
        make
        "$ar" rcs "$prefix/lib/libtalloc.a" bin/default/talloc*.o
        install -m 644 talloc.h "$prefix/include/talloc.h"
    )

    local proot_source="$abi_work/proot-source/proot-${PROOT_VERSION}"
    (
        cd "$proot_source"
        PATH="$wrappers:$PATH" make \
            -C src \
            CC="$cc" \
            LD="$cc" \
            STRIP="$strip" \
            OBJCOPY="$TOOLCHAIN/llvm-objcopy" \
            OBJDUMP="$TOOLCHAIN/llvm-objdump" \
            CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"${PROOT_VERSION}\\\" -I. -I$prefix/include" \
            CFLAGS="-Wall -Wextra -O2" \
            LDFLAGS="-L$prefix/lib -ltalloc -Wl,-z,noexecstack"
        "$strip" src/proot
        "$strip" "$proot_source/src/loader/loader"
    )

    local destination="$ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$destination"
    install -m 755 "$proot_source/src/proot" "$destination/libproot.so"
    install -m 755 "$proot_source/src/loader/loader" "$destination/libloader.so"
    echo "$abi: libproot.so $(hash_file "$destination/libproot.so")"
    echo "$abi: libloader.so $(hash_file "$destination/libloader.so")"
}

if [[ "$#" -eq 0 ]]; then
    set -- arm64-v8a
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a)   build_abi "$abi" "aarch64-linux-android" ;;
        armeabi-v7a) build_abi "$abi" "armv7a-linux-androideabi" ;;
        x86_64)      build_abi "$abi" "x86_64-linux-android" ;;
        *)
            echo "unknown ABI: $abi" >&2
            exit 1
            ;;
    esac
done
