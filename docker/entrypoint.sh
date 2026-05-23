#!/bin/bash
set -euo pipefail

JAVA_OPTS="-XX:+ExitOnOutOfMemoryError
  -Xmx${WAVES_HEAP_SIZE}
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  -Dlogback.stdout.level=${WAVES_LOG_LEVEL}
  -Dlogback.file.directory=${WVLOG}
  -Dlogback.file.level=TRACE
  -Dwaves.config.directory=/etc/waves
  -Dwaves.defaults.blockchain.type=${WAVES_NETWORK}
  -Dwaves.directory=${WVDATA}
  -Dwaves.rest-api.bind-address=${WAVES_REST_API_BIND:-127.0.0.1}
  ${JAVA_OPTS:-}"

# Log non-sensitive JVM options only
echo "Node starting with WAVES_NETWORK=${WAVES_NETWORK}, WAVES_HEAP_SIZE=${WAVES_HEAP_SIZE}" | tee -a ${WVLOG}/waves.log

# Write wallet secrets to a temp config file (not visible in ps/cmdline)
WAVES_SECRETS_CONF=$(mktemp /tmp/waves-secrets.XXXXXX.conf)
chmod 600 "$WAVES_SECRETS_CONF"
trap 'rm -f "$WAVES_SECRETS_CONF" /tmp/waves-combined.*.conf' EXIT

HAS_SECRETS=false
if [ -n "${WAVES_WALLET_SEED:-}" ] ; then
  printf 'waves.wallet.seed="%s"\n' "$WAVES_WALLET_SEED" >> "$WAVES_SECRETS_CONF"
  unset WAVES_WALLET_SEED
  HAS_SECRETS=true
fi

if [ -n "${WAVES_WALLET_PASSWORD:-}" ] ; then
  printf 'waves.wallet.password="%s"\n' "$WAVES_WALLET_PASSWORD" >> "$WAVES_SECRETS_CONF"
  unset WAVES_WALLET_PASSWORD
  HAS_SECRETS=true
fi

EXEC_ARGS=()
if [ $# -eq 0 ] && [ -f /etc/waves/waves.conf ] ; then
  if [ "$HAS_SECRETS" = true ] ; then
    # Create a wrapper config that includes secrets (higher priority) and user config
    COMBINED_CONF=$(mktemp /tmp/waves-combined.XXXXXX.conf)
    chmod 600 "$COMBINED_CONF"
    printf 'include file("/etc/waves/waves.conf")\ninclude file("%s")\n' "$WAVES_SECRETS_CONF" > "$COMBINED_CONF"
    EXEC_ARGS=("$COMBINED_CONF")
  else
    EXEC_ARGS=("/etc/waves/waves.conf")
  fi
else
  EXEC_ARGS=("$@")
fi

exec java $JAVA_OPTS -cp "$WAVES_INSTALL_PATH/lib/plugins/*:$WAVES_INSTALL_PATH/lib/*" com.decentralchain.Application "${EXEC_ARGS[@]}"
