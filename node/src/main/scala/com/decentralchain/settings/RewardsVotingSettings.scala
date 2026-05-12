package com.decentralchain.settings

import pureconfig.*

case class RewardsVotingSettings(desired: Option[Long]) derives ConfigReader
