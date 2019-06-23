package com.betway

import java.util.concurrent.TimeUnit

import com.surebetfinder.config.{Postgres, SureBetFinderConfiguration}
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Main {

  val configuration: SureBetFinderConfiguration = new SureBetFinderConfiguration().load("./betway.yml")
  val logger = Logger(configuration.siteName)

  def main(args: Array[String]): Unit = {

    logger.info(configuration.siteName + " scarper starts!")

    // postgres configuration
    val postgres = new Postgres(configuration)
    logger.info("Connection to Postgres DB works fine!")

    // retrieve leagues
    val scanner = new Scanner(postgres)
    val leagues = scanner.getLeagues()
    leagues.foreach(println)

    // threads execution
    val tasks = for {league <- leagues} yield Future { scanner.getEvents(league) }
    val aggregated = Future.sequence(tasks)
    Await.ready(aggregated, Duration(4, TimeUnit.MINUTES))

    // closing postgres
    postgres.closeConnection()

  }

}
