package com.decentralchain.tags

import org.scalatest.Tag

/** Tests that do real per-block RocksDB state appends (via Domain.appendBlock), as opposed to
  * in-process-only simulation (e.g. the HotStuff DST harness) — excluded from the push-gated
  * node-tests run, run nightly instead with a larger seed count.
  */
object SlowTest extends Tag("com.decentralchain.tags.SlowTest")
