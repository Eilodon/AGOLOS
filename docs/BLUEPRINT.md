# ZenB Blueprint

This document captures the high-level architecture and mapping to code.

- Mobile-first: small on-device SQLite store, WAL, synchronous=NORMAL
- Deterministic core: `zenb-core` offers deterministic state and BLAKE3 hash for reproducibility and attestation
- Event sourcing: events appended to `events` table; read models are projectors in `zenb-projectors`
- Encryption: XChaCha20-Poly1305 per-row payload encryption. Metadata stored plaintext but included in AAD via BLAKE3(meta)
- Crypto-shredding: per-session `session_keys` table stores wrapped session keys with master key
- Flush policy: runtime maintains in-memory buffer; triggers: length, bytes, elapsed (80ms), force
- Store append uses BEGIN IMMEDIATE for batch append and per-session seq validation using a single SELECT MAX(seq) per session => O(1) checks per session
