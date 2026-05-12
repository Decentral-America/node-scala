package com.decentralchain.features.api

import com.decentralchain.state.Height

case class ActivationStatus(height: Height, votingInterval: Int, votingThreshold: Int, nextCheck: Height, features: Seq[FeatureActivationStatus])
