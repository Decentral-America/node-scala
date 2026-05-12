package com.decentralchain.ride

import com.decentralchain.common.utils.Base64
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.lang.API
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.estimator.v3.ScriptEstimatorV3

object ScriptUtil {
  def from(src: String, libraries: Map[String, String] = Map.empty): Script =
    API
      .compile(
        input = src,
        estimator = ScriptEstimatorV3.latest,
        libraries = libraries
      )
      .flatMap(x => Script.fromBase64String(Base64.encode(x.bytes)))
      .explicitGet()
}
