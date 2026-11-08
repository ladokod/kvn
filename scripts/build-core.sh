#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../native/husi/libcore"
: "${ANDROID_HOME:?Set ANDROID_HOME to the SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 29.0.14206865}"
export PATH="$(go env GOPATH)/bin:$PATH"
go mod verify
go install github.com/sagernet/gomobile/cmd/gomobile github.com/sagernet/gomobile/cmd/gobind
# Keep upstream release build tags to avoid changing the network implementation.
gomobile bind -target="${KVN_CORE_TARGET:-android/arm64}" -androidapi 26 -trimpath -buildvcs=false \
 -javapkg=fr.husi -ldflags='-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.15.0-alpha.13' \
 -tags=with_quic,with_wireguard,with_openconnect,with_openvpn,with_tailscale,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0 \
 -o libcore.aar .
sha256sum libcore.aar > libcore.aar.sha256
