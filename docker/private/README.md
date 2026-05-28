# DecentralChain private node

The image is useful for developing dApps and other smart contracts on the DecentralChain blockchain.

## Getting started

To run the node,\
`docker run -d --name dcc-private-node -p 6869:6869 ghcr.io/decentral-america/node-scala:private`

To view node API documentation, open http://localhost:6869/

## Preserve blockchain state

If you want to keep the blockchain state, then just stop the container instead of killing it, and start it again when needed:\
`docker stop dcc-private-node`
`docker start dcc-private-node`

## Configuration details

The node is configured with:

- faster generation of blocks (**10 sec** interval)
- all features pre-activated
- custom chain id - **R**
- api_key `dcc-private-node`
- default miner account with all DecentralChain tokens (you can distribute these tokens to other accounts as you wish):
  ```
  rich account:
      Seed text:           <set via DCC_TEST_MINER_SEED env var — see .env.example>
      Seed:                <Base58 encoded>
      Account seed:        <derived from seed>
      Private account key: <derived from seed>
      Public account key:  <derived from seed>
      Account address:     <derived from seed>
  ```

Full node configuration is available on Github in `decentralchain.custom.conf`.
