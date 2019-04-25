package com.betway.domain

case class Outcome(id: Int, value: Double)

object Outcome {
  def empty = apply(-1, -1.0)
}