# The base image MUST be provided at build time — never use :latest in production.
# Build with: docker build --build-arg baseImage=ghcr.io/decentral-america/node-scala:<tag>@sha256:<digest>
ARG baseImage
FROM $baseImage
COPY decentralchain.custom.conf /etc/dcc/dcc.conf
