#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/ndk/28.2.13676358}"
source_dir="$project_root/.native-src"
mkdir -p "$source_dir" "$project_root/.native-cache"
export GOPATH="$project_root/.native-cache/go"
export GOCACHE="$project_root/.native-cache/build"
export GOTOOLCHAIN=local
export PATH="/opt/homebrew/bin:$PATH"
download_source() {
  local archive="$1" url="$2" expected="$3"
  if [[ ! -f "$source_dir/$archive" ]]; then curl --fail --location --retry 3 "$url" -o "$source_dir/$archive"; fi
  local actual
  actual="$(shasum -a 256 "$source_dir/$archive" | cut -d ' ' -f 1)"
  [[ "$actual" == "$expected" ]] || { echo "Source checksum mismatch: $archive" >&2; exit 1; }
  tar -xzf "$source_dir/$archive" -C "$source_dir"
}
download_source cloudflared.tar.gz https://codeload.github.com/cloudflare/cloudflared/tar.gz/refs/tags/2025.8.1 803d3e162dc91da6b3b4f7961f57c113fa818d45517758511f72d254d68a46ba
download_source frp.tar.gz https://codeload.github.com/fatedier/frp/tar.gz/refs/tags/v0.65.0 bbec0d1855e66c96e3a79ff97b8c74d9b1b45ec560aa7132550254d48321f7de
python3 "$project_root/scripts/patch-native.py"
case "$(uname -s)" in
  Darwin) ndk_host=darwin-x86_64 ;;
  Linux) ndk_host=linux-x86_64 ;;
  *) echo 'Build on macOS or Linux with Android NDK 28.2.' >&2; exit 1 ;;
esac
toolchain="$ndk_dir/toolchains/llvm/prebuilt/$ndk_host/bin"
go version
for abi in arm64-v8a armeabi-v7a x86_64; do
  case "$abi" in
    arm64-v8a) export GOARCH=arm64; compiler=aarch64-linux-android33-clang ;;
    armeabi-v7a) export GOARCH=arm GOARM=7; compiler=armv7a-linux-androideabi33-clang ;;
    x86_64) export GOARCH=amd64; compiler=x86_64-linux-android33-clang ;;
  esac
  export GOOS=android CGO_ENABLED=1 CC="$toolchain/$compiler"
  output_dir="$project_root/app/src/main/jniLibs/$abi"
  mkdir -p "$output_dir"
  (
    cd "$source_dir/frp-0.65.0"
    go build -mod=readonly -trimpath -buildmode=pie -tags netcgo -ldflags='-s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384"' -o "$output_dir/libfrpc.so" ./cmd/frpc
  )
  (
    cd "$source_dir/cloudflared-2025.8.1"
    go build -mod=vendor -trimpath -buildmode=pie -tags netcgo -ldflags='-s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384" -X main.Version=2025.8.1 -X main.BuildType=android-virtual-screen -X main.BuildTime=2025-08-12T00:00:00Z' -o "$output_dir/libcloudflared.so" ./cmd/cloudflared
  )
  "$toolchain/llvm-readelf" -l "$output_dir/libfrpc.so" > "$source_dir/frpc-$abi.elf.txt"
  "$toolchain/llvm-readelf" -l "$output_dir/libcloudflared.so" > "$source_dir/cloudflared-$abi.elf.txt"
done
mkdir -p "$project_root/app/src/main/assets/licenses"
cp "$source_dir/frp-0.65.0/LICENSE" "$project_root/app/src/main/assets/licenses/frp.txt"
cp "$source_dir/cloudflared-2025.8.1/LICENSE" "$project_root/app/src/main/assets/licenses/cloudflared.txt"
python3 "$project_root/scripts/native-manifest.py"
