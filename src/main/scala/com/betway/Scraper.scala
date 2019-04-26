package com.betway

import akka.actor.{ActorSystem, Props}
import akka.event.slf4j.Logger
import com.betway.actor.ScannerActor
import com.surebetfinder.config.SureBetFinderConfiguration

object Scraper {

  private val configuration: SureBetFinderConfiguration = new SureBetFinderConfiguration().load("./betway.yml")
  val logger = Logger(configuration.siteName)

  def main(args: Array[String]): Unit = {

    // create the ActorSystem
    val system: ActorSystem = ActorSystem("betway-scraper")

    logger.info("Creating Actorsystem...")
    logger.info(configuration.siteName + " scraper is starting...")

    // create a new actor, then send messages
    val scanner = system.actorOf(Props(classOf[ScannerActor]), "scanner")
    scanner ! configuration

    // shut down the ActorSystem when the work is finished
    system.terminate()

  }

}
