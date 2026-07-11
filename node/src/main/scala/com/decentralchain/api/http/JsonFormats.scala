package com.decentralchain.api.http

import com.decentralchain.account.Address
import com.decentralchain.lang.contract.meta.FunctionSignatures
import com.decentralchain.transaction.Transaction
import play.api.libs.json.*
import play.api.libs.json.Json.JsValueWrapper

trait JsonFormats {
  implicit lazy val dccAddressWrites: Writes[Address] = Writes(w => JsString(w.toString))

  implicit lazy val TransactionJsonWrites: OWrites[Transaction] = OWrites(_.json())

  implicit lazy val functionSignaturesWrites: Writes[FunctionSignatures] =
    (o: FunctionSignatures) =>
      Json.obj(
        "version"           -> o.version.toString,
        "callableFuncTypes" -> Json.obj(
          o.argsWithFuncName.map { case (functionName, args) =>
            val functionArgs: JsValueWrapper =
              args.map { case (argName, argType) =>
                Json.obj(
                  "name" -> argName,
                  "type" -> argType.name
                )
              }
            functionName -> functionArgs
          }.toSeq*
        )
      )

}
