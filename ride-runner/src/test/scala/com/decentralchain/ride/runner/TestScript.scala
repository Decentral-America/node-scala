package com.decentralchain.ride.runner

import com.decentralchain.common.utils.Base64
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.lang.API
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.estimator.v3.ScriptEstimatorV3

object TestScript {
  def scriptFrom(src: String): Script =
    API
      .compile(input = src, ScriptEstimatorV3.latest)
      .flatMap(x => Script.fromBase64String(Base64.encode(x.bytes)))
      .explicitGet()
}
