#!/bin/bash
set -euo pipefail

JAVA_OPTS="-XX:+ExitOnOutOfMemoryError
  -Xmx${DCC_HEAP_SIZE}
  --enable-native-access=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  -Dlogback.stdout.level=${DCC_LOG_LEVEL}
  -Dlogback.file.directory=${DCC_LOG}
  -Dlogback.file.level=TRACE
  -Ddcc.config.directory=/etc/dcc
  -Ddcc.defaults.blockchain.type=${DCC_NETWORK}
  -Ddcc.directory=${DCC_DATA}
  -Ddcc.rest-api.bind-address=${DCC_REST_API_BIND:-127.0.0.1}
  ${JAVA_OPTS:-}"

if [ "${DCC_LOG_JAVA_OPTS:-}" = "true" ] ; then
  echo "JAVA_OPTS=${JAVA_OPTS}" | tee -a "${DCC_LOG}/dcc.log"
fi

# Write wallet secrets to a temp config file (not visible in ps/cmdline)
DCC_SECRETS_CONF=$(mktemp /tmp/dcc-secrets.XXXXXX.conf)
chmod 600 "$DCC_SECRETS_CONF"
trap 'rm -f "$DCC_SECRETS_CONF" /tmp/dcc-combined.*.conf' EXIT

HAS_SECRETS=false
if [ -n "${DCC_WALLET_SEED:-}" ] ; then
  printf 'dcc.wallet.seed="%s"\n' "$DCC_WALLET_SEED" >> "$DCC_SECRETS_CONF"
  unset DCC_WALLET_SEED
  HAS_SECRETS=true
fi

if [ -n "${DCC_WALLET_PASSWORD:-}" ] ; then
  printf 'dcc.wallet.password="%s"\n' "$DCC_WALLET_PASSWORD" >> "$DCC_SECRETS_CONF"
  unset DCC_WALLET_PASSWORD
  HAS_SECRETS=true
fi

EXEC_ARGS=()
if [ $# -eq 0 ] && [ -f /etc/dcc/dcc.conf ] ; then
  if [ "$HAS_SECRETS" = true ] ; then
    # Create a wrapper config that includes secrets (higher priority) and user config
    COMBINED_CONF=$(mktemp /tmp/dcc-combined.XXXXXX.conf)
    chmod 600 "$COMBINED_CONF"
    printf 'include file("/etc/dcc/dcc.conf")\ninclude file("%s")\n' "$DCC_SECRETS_CONF" > "$COMBINED_CONF"
    EXEC_ARGS=("$COMBINED_CONF")
  else
    EXEC_ARGS=("/etc/dcc/dcc.conf")
  fi
else
  EXEC_ARGS=("$@")
fi

# lib/* before lib/plugins/* so node-compiled classes take priority over any
# extension JAR that bundles stale copies of the same classes (e.g. dcc-grpc.jar
# shipping old Block$Header without field 14 would shadow the correct class).
exec java $JAVA_OPTS -cp "$DCC_INSTALL_PATH/lib/*:$DCC_INSTALL_PATH/lib/plugins/*" com.decentralchain.Application "${EXEC_ARGS[@]}"
