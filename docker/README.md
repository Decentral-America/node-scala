# DecentralChain Node in Docker

Run a full DecentralChain blockchain node using Docker. The image supports all three networks — mainnet, testnet, and stagenet — and can be configured via environment variables or a mounted configuration file.

## Quick start

```sh
docker run -d \
  --name dcc-node \
  -p 6869:6869 \
  -p 6868:6868 \
  -e WAVES_NETWORK=MAINNET \
  ghcr.io/decentral-america/node-scala:mainnet-latest
```

> **Port 6869** — REST API (HTTP)  
> **Port 6868** — P2P node communication

---

## Image tags

| Tag | Description |
|-----|-------------|
| `mainnet-latest` | Latest build targeting DecentralChain mainnet |
| `testnet-latest` | Latest build targeting DecentralChain testnet |
| `stagenet-latest` | Latest build targeting DecentralChain stagenet |
| `sha-<commit>` | Pinned build by git commit SHA |

---

## Configuration

### Via environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `WAVES_NETWORK` | `MAINNET` | Target network: `MAINNET`, `TESTNET`, or `STAGENET` |
| `WAVES_HEAP_SIZE` | `2g` | JVM heap size passed to `-Xmx` |
| `WAVES_LOG_LEVEL` | `DEBUG` | Log verbosity: `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `WAVES_WALLET_SEED` | — | Base58-encoded wallet seed (written to `wallet.dat` at startup) |
| `WAVES_WALLET_PASSWORD` | — | Password for the wallet file |
| `JAVA_OPTS` | — | Additional JVM options (e.g. `-Dlogback.configurationFile=...`) |

### Via mounted config file

Override any setting by mounting a custom HOCON configuration file:

```sh
docker run -d \
  --name dcc-node \
  -p 6869:6869 \
  -p 6868:6868 \
  -v /path/to/node.conf:/etc/decentralchain/node.conf \
  -e WAVES_NETWORK=MAINNET \
  ghcr.io/decentral-america/node-scala:mainnet-latest
```

The entrypoint merges the mounted file with the built-in network defaults using HOCON include resolution.

---

## Persisting data

Mount a volume to keep blockchain state across container restarts:

```sh
docker run -d \
  --name dcc-node \
  -p 6869:6869 \
  -p 6868:6868 \
  -v dcc-data:/var/lib/decentralchain \
  -e WAVES_NETWORK=MAINNET \
  ghcr.io/decentral-america/node-scala:mainnet-latest
```

The node stores its data at `/var/lib/decentralchain` inside the container.

---

## Docker Compose example

```yaml
services:
  dcc-node:
    image: ghcr.io/decentral-america/node-scala:mainnet-latest
    container_name: dcc-node
    restart: unless-stopped
    ports:
      - "6869:6869"
      - "6868:6868"
    environment:
      WAVES_NETWORK: MAINNET
      WAVES_HEAP_SIZE: 4g
      WAVES_LOG_LEVEL: INFO
      WAVES_WALLET_SEED: "${DCC_WALLET_SEED}"
      WAVES_WALLET_PASSWORD: "${DCC_WALLET_PASSWORD}"
    volumes:
      - dcc-data:/var/lib/decentralchain
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:6869/node/status"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

volumes:
  dcc-data:
```

Store secrets in a `.env` file (never commit to version control):

```
DCC_WALLET_SEED=your_base58_wallet_seed_here
DCC_WALLET_PASSWORD=your_wallet_password_here
```

---

## REST API

Once running, the REST API is available at `http://localhost:6869`. Key endpoints:

| Endpoint | Description |
|----------|-------------|
| `GET /node/status` | Node synchronisation status |
| `GET /node/version` | Node version string |
| `GET /blocks/height` | Current blockchain height |
| `GET /addresses` | List node wallet addresses |

Full API reference: see the `swagger.json` bundled in the image or the online documentation in the [docs](../docs) directory.

---

## Building from source

```sh
# Build the distribution tarball first
sbt --batch buildTarballsForDocker

# Build the Docker image
docker build -t decentralchain/node-scala:local ./docker
```

---

## Security notes

- The container runs as a non-root user (`waves`, UID 999) by default.
- Wallet seeds are written to a temporary file with `chmod 600` and cleaned up on exit.
- The `WAVES_WALLET_SEED` environment variable is not logged.
- The REST API is bound to `0.0.0.0` by default. In production, place it behind a reverse proxy and restrict public exposure.

For the project security policy, see [SECURITY.md](../SECURITY.md).
