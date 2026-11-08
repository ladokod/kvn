#!/usr/bin/env bash
set -euo pipefail
fixture_bin_dir="${1:?fixture binary directory required}"
mkdir -p "$fixture_bin_dir"
cd "$(dirname "$0")/../native/husi/libcore"
# Same pinned sing-box source as the client. No Cronet outbound is needed by
# this disposable local H2 CONNECT server, so no Linux binary download is used.
CGO_ENABLED=0 go build -trimpath -buildvcs=false -tags=badlinkname,tfogo_checklinkname0 \
  -ldflags='-s -w -checklinkname=0' -o "$fixture_bin_dir/sing-box" ../../../tools/fixture-server/main.go
