#!/usr/bin/env bash
set -euo pipefail
fixture_binary="${1:?path to fixture sing-box required}"
fixture_dir=$(mktemp -d)
fixture_pid=''
cleanup() {
  if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
  if [[ -f "$fixture_dir/metrics.json" ]]; then
    python3 -c 'import json,sys; print("FIXTURE.metrics="+json.dumps(json.load(open(sys.argv[1])), sort_keys=True))' "$fixture_dir/metrics.json"
  fi
  adb shell 'rm /data/local/tmp/kvn-fixture.json' >/dev/null 2>&1 || true
  python3 -c 'import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$fixture_dir"
}
trap cleanup EXIT
python3 tools/tun_fixture.py --binary "$fixture_binary" --directory "$fixture_dir" >"$fixture_dir/ready.log" 2>&1 &
fixture_pid=$!
for attempt in $(seq 1 100); do
  if [[ -s "$fixture_dir/fixture.json" ]]; then break; fi
  if ! kill -0 "$fixture_pid" 2>/dev/null; then echo 'Fixture failed to start (private configuration omitted)'; exit 1; fi
  sleep 0.1
done
test -s "$fixture_dir/fixture.json"
gradle -PkvnTestAbi=x86_64 :app:installDebug :app:installDebugAndroidTest :probe:installDebug --no-daemon
adb push "$fixture_dir/fixture.json" /data/local/tmp/kvn-fixture.json
adb shell appops set ru.ladokod.kvn ACTIVATE_VPN allow
timeout 300s gradle -PkvnTestAbi=x86_64 :app:connectedDebugAndroidTest --no-daemon
