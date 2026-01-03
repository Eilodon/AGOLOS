#!/usr/bin/env bash
set -euo pipefail

# Helper to generate uniffi bindings for zenb-uniffi
OUT_DIR="artifacts/uniffi"
mkdir -p "$OUT_DIR"

if ! command -v uniffi-bindgen >/dev/null 2>&1; then
  echo "uniffi-bindgen not found; installing via cargo (this may take a few minutes)"
  cargo install --locked uniffi-bindgen-cli
fi

echo "Generating C language bindings (smoke test)"
uniffi-bindgen generate crates/zenb-uniffi/src/lib.rs --language c --out-dir "$OUT_DIR"

echo "Bindings written to $OUT_DIR"
