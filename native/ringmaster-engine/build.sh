#!/bin/sh
# Builds the RingMASTER Engine for Linux x86_64 and aarch64 (static musl) and packs the bundle the app hands to a guest.
#
#   sh native/ringmaster-engine/build.sh [OUT]      (OUT defaults to native/ringmaster-engine/dist)
#
# Needs: rustup with the two musl targets, cargo-zigbuild and zig on PATH (zig is the C compiler for ring and the linker).
#   rustup target add x86_64-unknown-linux-musl aarch64-unknown-linux-musl
#   cargo install --locked cargo-zigbuild;  pip install ziglang  (or zig from ziglang.org)
# A binary built on a developer's system is for testing only; releases come from the release workflow.
set -eu
CRATE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$CRATE/dist}
TARGET_DIR=${CARGO_TARGET_DIR:-$CRATE/target}
version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$CRATE/Cargo.toml" | head -n 1)

cargo zigbuild --release --locked --manifest-path "$CRATE/Cargo.toml" \
    --target x86_64-unknown-linux-musl --target aarch64-unknown-linux-musl

# One bundle per architecture: what is delivered into a guest is half the size of a bundle with both.
for arch in x86_64 aarch64; do
    name=ringmaster-engine-$version-linux-$arch
    stage=$OUT/$name
    rm -rf "$stage"
    mkdir -p "$stage"
    cp "$TARGET_DIR/$arch-unknown-linux-musl/release/ringmaster-engine" "$stage/ringmaster-engine-$arch"
    cp "$CRATE/install/install.sh" "$CRATE/install/uninstall.sh" "$stage/"
    chmod 0755 "$stage"/ringmaster-engine-* "$stage"/*.sh
    (cd "$stage" && sha256sum "ringmaster-engine-$arch" install.sh uninstall.sh > SHA256SUMS)
    # Reproducible archive: fixed owner, order and times, no gzip time stamp.
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -C "$OUT" -cf - "$name" | gzip -9n > "$OUT/$name.tar.gz"
    rm -rf "$stage"
done
(cd "$OUT" && sha256sum ringmaster-engine-$version-linux-*.tar.gz > ringmaster-engine-$version-SHA256SUMS && cat ringmaster-engine-$version-SHA256SUMS)
