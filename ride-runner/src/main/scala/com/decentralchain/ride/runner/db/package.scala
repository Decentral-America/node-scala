package com.decentralchain.ride.runner

import com.decentralchain.state.Height

import scala.util.NotGiven

package object db {
  type Heights = Vector[Height]
  val EmptyHeights: Heights = Vector.empty

  type =:!=[A, B] = NotGiven[A =:= B]
  type <:!<[A, B] = NotGiven[A <:< B]
}
