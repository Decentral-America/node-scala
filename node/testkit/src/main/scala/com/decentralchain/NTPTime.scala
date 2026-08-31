package com.decentralchain

import com.decentralchain.utils.{SystemTime, Time}
import org.scalatest.Suite

trait NTPTime { suite: Suite =>
  // NOTE: deliberately typed as SystemTime.type (not the abstract Time trait, unlike upstream's
  // equivalent post-#4076 NTPTime.scala) so ntpTime.getTimestamp() -- SystemTime's own
  // monotonically-increasing helper, not part of the Time trait -- stays available. Many DCC test
  // suites call ntpTime.getTimestamp() repeatedly in quick succession (property-based generators,
  // (1 to N).map loops) to mint otherwise-identical transactions that only differ by timestamp;
  // switching this to correctedTime() (upstream's literal choice) reintroduces real, observed
  // AlreadyInTheState failures wherever two calls land in the same millisecond, since correctedTime()
  // has no such guarantee. ntpTime still widens to Time at any call site that expects the trait.
  protected val ntpTime: SystemTime.type = SystemTime

  protected def ntpNow: Long = ntpTime.getTimestamp()
}
