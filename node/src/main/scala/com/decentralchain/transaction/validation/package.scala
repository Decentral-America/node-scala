package com.decentralchain.transaction

import cats.data.ValidatedNel
import com.decentralchain.lang.ValidationError

package object validation {
  type ValidatedV[A] = ValidatedNel[ValidationError, A]
  type ValidatedNV   = ValidatedV[Unit]
}
