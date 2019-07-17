package com.betway

import com.surebetfinder.config.{Postgres, SureBetFinderConfiguration}
import com.typesafe.scalalogging.Logger

object Main {

  val configuration: SureBetFinderConfiguration = new SureBetFinderConfiguration().load("./betway.yml")
  val logger = Logger(configuration.siteName)
  private val SPORTS = Seq("soccer", "tennis")

  def main(args: Array[String]): Unit = {

    logger.info(configuration.siteName + " scarper starts!")

    // postgres configuration
    val postgres = new Postgres(configuration)
    logger.info("Connection to Postgres DB works fine!")

    // retrieve leagues
    val scanner = new Scanner(postgres)

    SPORTS.foreach { sport =>
      logger.info("--- " + sport.toUpperCase() + " ---")
      val leagues = scanner.getLeagues(sport)
      // getting events execution
      leagues.foreach { league =>
        println(league)
        scanner.getEvents(league)
      }
    }

    // closing postgres
    postgres.closeConnection()

  }

}
