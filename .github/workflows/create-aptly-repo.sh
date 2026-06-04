#!/usr/bin/env bash
set -euo pipefail

PASSPHRASE_FILE=$(mktemp)
trap 'rm -f private-key.asc "$PASSPHRASE_FILE"' EXIT

echo "$MAVEN_GPG_PRIVATE_KEY" > private-key.asc
gpg --batch --import-options import-show --import private-key.asc

printf '%s' "$MAVEN_GPG_PASSPHRASE" > "$PASSPHRASE_FILE"
chmod 600 "$PASSPHRASE_FILE"

all_deb_packages=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/Decentral-America/node-scala/releases |\
  jq --raw-output '.[].assets[]|select(.name | test("\\.deb$"))|select(now - (.created_at|fromdate) < 1209600)|[.name,.browser_download_url,.digest]|@csv' |\
  tr -d \")

mkdir -p packages

for deb_package in ${all_deb_packages} ; do
  IFS="," read -r deb_file_name deb_url digest <<< "$deb_package"
  wget -nv -O "packages/$deb_file_name" "$deb_url"
  if [[ "$digest" != "null" ]] ; then
    echo "$(echo "$digest" | cut -d: -f2) packages/$deb_file_name" | sha256sum --check --status - && echo CHECKSUM OK
  fi
done

aptly repo create main
aptly repo add main packages/
aptly publish repo -batch -architectures="arm64,amd64,all" -distribution=stable -gpg-key="$MAVEN_GPG_KEY_ID" -passphrase-file="$PASSPHRASE_FILE" main

gpg --armor --export "$MAVEN_GPG_KEY_ID" > /home/runner/.aptly/public/pubkey.txt

rm -rf .gnupg
current_date=$(date)
latest_release=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" \
  "https://api.github.com/repos/Decentral-America/node-scala/releases?per_page=1" |\
  jq --raw-output '.[0]|"<a href=\"\(.html_url)\">\(.name)</a>"')

cat > /home/runner/.aptly/public/index.html <<EOF
<html>
<head>
<title>DCC Node APT Repository</title>
</head>
<body>
<h1>DCC Node APT Repository</h1>
<p>Latest release: $latest_release</p>
<h3>Adding This Repository</h3>
<pre>
echo "deb [signed-by=/etc/apt/keyrings/decentralchain.asc] https://apt.decentralchain.io stable main" | sudo tee /etc/apt/sources.list.d/decentralchain.list
# For releases older than Debian 12 and Ubuntu 22.04, create the directory first:
sudo mkdir -p /etc/apt/keyrings; sudo chmod 755 /etc/apt/keyrings
sudo wget -O /etc/apt/keyrings/decentralchain.asc https://apt.decentralchain.io/pubkey.txt
sudo apt-get update
</pre>
<h3>Installing DCC Node</h3>
<p>Mainnet:</p>
<pre>
sudo apt-get install dcc
</pre>
<p>Testnet:</p>
<pre>
sudo apt-get install dcc-testnet
</pre>
<small>Last update: $current_date</small>
</body>
</html>
EOF
