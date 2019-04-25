package com.betway.domain

case class Market(id: Int, name: String, outcomes: List[Int] = List.empty)

object Market {
  def empty = apply(-1, "", List.empty)
}
