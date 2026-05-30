# The base image MUST be provided at build time — never use :latest in production.
# Build with: docker build --build-arg baseImage=ghcr.io/decentral-america/node-scala:<tag>@sha256:<digest>
ARG baseImage
FROM $baseImage
COPY decentralchain.custom.conf /etc/dcc/dcc.conf
COPY local.conf /etc/dcc/local.conf

# Deterministic defaults for local development and integration tests.
# The genesis block in decentralchain.custom.conf sends all tokens to the
# address derived from this seed: 3M4qwDomRabJKLZxuXhwfqLApQkU592nWxF
# Override via environment variables for non-default setups.
ENV DCC_WALLET_SEED="dcc private node seed with dcc tokens"
ENV DCC_WALLET_PASSWORD="test"
ENV DCC_REST_API_BIND="0.0.0.0"
