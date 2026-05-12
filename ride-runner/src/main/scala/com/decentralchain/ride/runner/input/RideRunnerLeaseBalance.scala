package com.decentralchain.ride.runner.input

import com.decentralchain.ride.runner.input.RideRunnerInputParser.given
import com.decentralchain.transaction.TxNonNegativeAmount
import pureconfig.ConfigReader

case class RideRunnerLeaseBalance(
    in: TxNonNegativeAmount = TxNonNegativeAmount.unsafeFrom(0),
    out: TxNonNegativeAmount = TxNonNegativeAmount.unsafeFrom(0)
) derives ConfigReader
