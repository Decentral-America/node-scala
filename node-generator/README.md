# node-generator

Manual transaction-load generator that sends live transactions directly to
one or more running node REST APIs. Not wired into any CI pipeline by
design — this generates real chain activity against a real deployment, so
it's meant to be run deliberately by an operator, not on every push.

Distinct from `DecentralChain/apps/load-tester` (the Rust TPS/latency
benchmark used by `infra/.github/workflows/stress-test.yml`): that tool
measures raw transfer throughput. This tool exercises specific transaction
*shapes* — multisig cycles, oracle data entries, smart-contract (SetScript)
load, and dynamic-rate transfers — that the generic TPS tool doesn't cover.

## Usage

```sh
sbt "node-generator/run -c <config-file> <mode> [mode-options]"
```

Modes: `narrow` (transfers between a fixed account set), `wide` (transfers
to many recipients, `-la` to limit), `dyn-wide` (like wide but with a
growing transaction rate, `-s`/`-g`/`-m`), `multisig` (fund → set multisig
script → spend cycle, `-first` to run the one-time script-setting step),
`oracle` (data-entry transactions, `-e` for the DataEntry value), `swarm`
(SetScript + Transfer + Exchange transaction mix, `-st`/`-tt`/`-ct`/`-et`
control the ratio and script complexity).

Run `sbt "node-generator/run --help"` for the full option list, including
worker/timeout/reconnect settings shared across all modes.

## ⚠ Default config points at stale, pre-rebrand node addresses

`src/main/resources/application.conf`'s `send-to` list is a set of IPs that
predate the Waves→DecentralChain rebrand (`refactor!: complete Waves→DCC
namespace migration`) — they were never updated to point at any real
DecentralChain node. **Do not run this tool with the default config** — it
will either fail to connect or, worse, connect to whatever now holds those
IPs. Always pass `-c` with a config file pointing at real target node(s),
following the `GeneratorSettings`/`NodeAddress` shape in
`src/main/scala/com/decentralchain/generator/GeneratorSettings.scala`
(`network-address` for the P2P port, `api-address` for the REST API), and
`accounts.conf`'s format for the funded account seeds to send from.
