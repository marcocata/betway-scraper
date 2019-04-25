package com.betway

import akka.actor.{ActorSystem, Props}
import com.betway.actor.ScannerActor
import com.surebetfinder.config.SureBetFinderConfiguration
import com.surebetfinder.db.Postgres

object Scraper {

  private val configuration: SureBetFinderConfiguration = new SureBetFinderConfiguration().load("./betway.yml")

  def main(args: Array[String]): Unit = {

    // create the ActorSystem
    val system: ActorSystem = ActorSystem("betway-scraper")
    system.log.info("Creating Actorsystem...")
    system.log.info(configuration.siteName + " scraper is starting...")

    // postgres initialization
    val postgres = new Postgres(configuration)
    system.log.info("Connection to Postgres DB works fine!")

    // create a new actor, then send messages
    val scanner = system.actorOf(Props(classOf[ScannerActor]), "scanner")
    scanner ! postgres

    // shut down the ActorSystem when the work is finished
    system.terminate()

  }

}
