package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.ride.runner.db.HasTestDb
import com.decentralchain.{BaseTestSuite, HasTestAccounts}

trait DiskTestSuite extends BaseTestSuite with HasTestDb with HasTestAccounts
