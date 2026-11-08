#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dependency-sources
# This is the gitlink in the EXACT prebuilt Cronet module d5437a0df01a.
# Include the complete source tree, including modified MPL-covered NSPR files.
source_dir=$(mktemp -d)
trap 'find "$source_dir" -type f -delete; find "$source_dir" -depth -type d -empty -delete' EXIT
git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/SagerNet/naiveproxy.git
git -C "$source_dir" fetch --depth=1 origin be1be963131154ba82761a7ed85ba426b2027319
git -C "$source_dir" archive --format=tar.gz --output="$PWD/dependency-sources/cronet-naiveproxy-be1be963-source.tar.gz" FETCH_HEAD
sha256sum dependency-sources/cronet-naiveproxy-be1be963-source.tar.gz > dependency-sources/cronet-naiveproxy-be1be963-source.tar.gz.sha256
