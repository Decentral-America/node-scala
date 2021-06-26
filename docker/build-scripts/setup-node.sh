#!/bin/bash

# Create user
groupadd -r decentralchain --gid=999
useradd -r -g decentralchain --uid=999 --home-dir=$WVDATA --shell=/bin/bash decentralchain

# Install DEB packages
dpkg -i /tmp/decentralchain.deb || exit 1
if [[ $ENABLE_GRPC == "true" ]]; then
  echo "Installing gRPC server"
  dpkg -i /tmp/decentralchain-grpc-server.deb || exit 1
fi

# Set permissions
chown -R decentralchain:decentralchain $WVDATA $WVLOG && chmod 777 $WVDATA $WVLOG

rm /etc/decentralchain/waves.conf # Remove example config
cp /tmp/entrypoint.sh /usr/share/decentralchain/bin/entrypoint.sh
chmod +x /usr/share/decentralchain/bin/entrypoint.sh

# Cleanup
rm -rf /tmp/*
