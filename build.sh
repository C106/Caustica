#!/usr/bin/env bash

set -Eeuo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly THIRD_PARTY_DIR="${ROOT_DIR}/third_party"
readonly DOWNLOAD_DIR="${THIRD_PARTY_DIR}/downloads"

readonly VULKAN_SDK_VERSION="1.4.350.0"
readonly VULKAN_SDK_SHA256="b65f068ab36263559da49d7cacd7e7b9df23824ca8b68ccc522a2b06f5725df2"
readonly VULKAN_ARCHIVE="vulkansdk-linux-x86_64-${VULKAN_SDK_VERSION}.tar.xz"
readonly VULKAN_URL="https://sdk.lunarg.com/sdk/download/${VULKAN_SDK_VERSION}/linux/${VULKAN_ARCHIVE}"

readonly DLSS_SDK_REF="v310.7.0"
readonly DLSS_ARCHIVE="DLSS-${DLSS_SDK_REF}.tar.gz"
readonly DLSS_URL="https://github.com/NVIDIA/DLSS/archive/refs/tags/${DLSS_SDK_REF}.tar.gz"

log() {
	printf '\n==> %s\n' "$*"
}

die() {
	printf 'error: %s\n' "$*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

download() {
	local url="$1"
	local destination="$2"

	if [[ -s "${destination}" ]]; then
		return
	fi

	mkdir -p -- "$(dirname -- "${destination}")"
	curl --fail --location --retry 5 --retry-all-errors --retry-delay 3 \
		--connect-timeout 20 --output "${destination}.part" "${url}"
	mv -- "${destination}.part" "${destination}"
}

check_environment() {
	[[ "$(uname -s)" == "Linux" ]] || die "this script currently builds the Linux package only"
	[[ "$(uname -m)" == "x86_64" ]] || die "this script requires an x86_64 host"

	for command in java cmake curl tar sha256sum; do
		require_command "${command}"
	done

	local java_major
	java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
	[[ -n "${java_major}" && "${java_major}" -ge 25 ]] || \
		die "Java 25 or newer is required (current: $(java -version 2>&1 | head -n 1))"
}

prepare_vulkan_sdk() {
	local candidate="${VULKAN_SDK:-}"
	if [[ -x "${candidate}/bin/slangc" ]] && \
		[[ "$("${candidate}/bin/slangc" -version 2>&1 | head -n 1)" == "2026.8" ]]; then
		printf '%s\n' "${candidate}"
		return
	fi

	local install_root="${THIRD_PARTY_DIR}/vulkan-sdk"
	local sdk_root="${install_root}/${VULKAN_SDK_VERSION}/x86_64"
	local archive="${DOWNLOAD_DIR}/${VULKAN_ARCHIVE}"

	if [[ ! -x "${sdk_root}/bin/slangc" ]]; then
		log "Downloading Vulkan SDK ${VULKAN_SDK_VERSION}" >&2
		download "${VULKAN_URL}" "${archive}"
		echo "${VULKAN_SDK_SHA256}  ${archive}" | sha256sum --check --status || {
			rm -f -- "${archive}"
			die "Vulkan SDK checksum verification failed; rerun to download it again"
		}
		mkdir -p -- "${install_root}"
		tar -xf "${archive}" -C "${install_root}"
	fi

	# build.gradle probes VULKAN_SDK/Bin before falling back to PATH.
	ln -sfn bin "${sdk_root}/Bin"
	printf '%s\n' "${sdk_root}"
}

prepare_dlss_sdk() {
	local candidate="${DLSS_SDK:-}"
	if [[ -f "${candidate}/include/nvsdk_ngx.h" ]]; then
		printf '%s\n' "${candidate}"
		return
	fi

	local sdk_root="${THIRD_PARTY_DIR}/DLSS"
	local archive="${DOWNLOAD_DIR}/${DLSS_ARCHIVE}"
	if [[ ! -f "${sdk_root}/include/nvsdk_ngx.h" ]]; then
		log "Downloading DLSS SDK ${DLSS_SDK_REF}" >&2
		download "${DLSS_URL}" "${archive}"
		rm -rf -- "${sdk_root}"
		mkdir -p -- "${sdk_root}"
		tar -xzf "${archive}" -C "${sdk_root}" --strip-components=1
	fi
	printf '%s\n' "${sdk_root}"
}

build_native_shim() {
	local generator_args=()
	if command -v ninja >/dev/null 2>&1; then
		generator_args=(-G Ninja)
	fi

	log "Building Linux NGX shim"
	DLSS_SDK="${DLSS_SDK_ROOT}" VULKAN_SDK="${VULKAN_SDK_ROOT}" \
		cmake -S "${ROOT_DIR}/native/ngx_shim" \
		-B "${ROOT_DIR}/build/cmake/ngx_shim" \
		"${generator_args[@]}" -DCMAKE_BUILD_TYPE=Release
	cmake --build "${ROOT_DIR}/build/cmake/ngx_shim" --config Release
}

build_mod() {
	local gradle_tasks=(build)
	if (($# > 0)); then
		gradle_tasks=("$@")
	fi

	log "Running Gradle ${gradle_tasks[*]}"
	PATH="${VULKAN_SDK_ROOT}/bin:${PATH}" \
	DLSS_SDK="${DLSS_SDK_ROOT}" \
	VULKAN_SDK="${VULKAN_SDK_ROOT}" \
		bash "${ROOT_DIR}/gradlew" "${gradle_tasks[@]}" --no-daemon
}

report_artifacts() {
	local artifacts=()
	while IFS= read -r -d '' artifact; do
		artifacts+=("${artifact}")
	done < <(find "${ROOT_DIR}/build/libs" -maxdepth 1 -type f -name '*.jar' \
		! -name '*-sources.jar' ! -name '*-dev.jar' -print0 2>/dev/null)

	((${#artifacts[@]} > 0)) || return
	log "Build artifacts"
	sha256sum "${artifacts[@]}"
}

main() {
	cd -- "${ROOT_DIR}"
	check_environment
	mkdir -p -- "${DOWNLOAD_DIR}"

	VULKAN_SDK_ROOT="$(prepare_vulkan_sdk)"
	DLSS_SDK_ROOT="$(prepare_dlss_sdk)"
	readonly VULKAN_SDK_ROOT DLSS_SDK_ROOT

	log "Using Java $(java -version 2>&1 | head -n 1)"
	log "Using Slang $("${VULKAN_SDK_ROOT}/bin/slangc" -version 2>&1 | head -n 1)"
	build_native_shim
	build_mod "$@"
	report_artifacts
}

main "$@"
