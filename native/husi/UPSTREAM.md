Vendored source: https://github.com/xchacha20-poly1305/husi
Release v2.3.4: 0822cc7b34ae3c57ee2840d553ca8cf31cf64867
Compared dev: de2176416af298d50bce4e160b0eeb6093ae61f9

The libcore directory is copied in full, with these KVN modifications:
- kvn_android.go: expose StartConfig and CheckKvnConfig to Kotlin without a
  profile database or plugin protobuf request.
- platform_box.go: propagate protect(fd)==false as an error instead of ignoring it.

Original module path and Java package are retained for compatibility and attribution.
They are not the application name. KVN is an independent derivative, not affiliated
with the upstream authors. See LICENSE, libcore/LICENSE_libbox and ../../LICENSE.

Network dependency graph is pinned by go.mod and go.sum; no dev dependency upgrade.
Core build uses upstream feature tags. Go module declares 1.27; KVN CI pins 1.27.0.
Upstream buildScript/init/version.sh pins Go 1.27.3 / NDK 30.0.16248370.
KVN initially uses NDK 29.0.14206865, Android API 26, Java 17 for its existing AGP.
Compatibility of this toolchain must be established by a completed build; see report.
