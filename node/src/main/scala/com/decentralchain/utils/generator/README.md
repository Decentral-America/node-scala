# utils/generator

Manual dev tools for synthesizing blockchain state offline (no live node
required, no network I/O). Not wired into any CI pipeline by design — see
usage below for how to invoke each one manually.

Distinct from `node-generator/` (sends real transactions to a live node's
REST API) and `DecentralChain/apps/load-tester` (live TPS/latency
benchmarking): these generate a blockchain file entirely in-process,
useful for testing chain-processing code paths (block validation, RocksDB
writer performance, etc.) without needing a running network at all.

## `BlockchainGeneratorApp`

Forges a synthetic chain of `--blocks` blocks against a genesis config, at
a target average block time, and writes the result to an output file.

```sh
sbt "node/runMain com.decentralchain.utils.generator.BlockchainGeneratorApp \
  -gc <genesis-config-file> -o <output-file> -b <block-count> \
  [-c <node-config-file>] [-t <target-avg-time-ms>] [-mc <mining-conflict-interval-ms>]"
```

Run with `--help` for the exact option list (`-gc`/`-o`/`-b` are the
essentials; `-c`/`-t`/`-mc` are optional tuning).
