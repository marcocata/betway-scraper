package com.betway.actor

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, OneForOneStrategy, Props}
import akka.event.Logging
import com.betway.Scraper.logger
import com.surebetfinder.config.SureBetFinderConfiguration
import com.surebetfinder.db.Postgres
import com.surebetfinder.domain.League
import com.surebetfinder.utils.BashUtils
import org.postgresql.util.PSQLException
import spray.json._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

class ScannerActor extends Actor {

  private val log = Logging(context.system, this)

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 3) {
      case psqlEx: PSQLException =>
        log.error("Postgres SQL connection error.")
        log.error(psqlEx.getMessage)
        Stop
    }

  def receive: PartialFunction[Any, Unit] = {
    case configuration: SureBetFinderConfiguration =>
      val postgres = new Postgres(configuration)
      logger.info("Connection to Postgres DB works fine!")

      val cmd = Seq(
        "curl",
        "https://sports.betway.it/api/Events/V2/GetCategoryDetails?t=f01a3f5a-0ed5-4011-815a-9e6a0796c478",
        "-H",
        "Content-Type: application/json; charset=UTF-8",
        "--data-binary",
        """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,\"CategoryCName\":\"soccer\"}"""
      )
      Try(BashUtils.executeCmd(cmd)) match {
        case Success(cmdResult) =>
          val leagues = extractLeagues(cmdResult)
          leagues.foreach(league => context.actorOf(Props(classOf[ScraperActor])) ! (league, postgres))
          Await.ready(context.system.whenTerminated, Duration(1, TimeUnit.SECONDS))

        case Failure(fail) =>
          log.error("Some error has occured while executing " + cmd.mkString + " command!")
          log.error(fail.getMessage)
      }
    case _                  => log.error("It was received an invalid parameter! Excepted: Postgres")
  }

  def extractLeagues(message: String): Vector[League] = {
    val json = message.parseJson

    val leagues = json.asJsObject.fields.get("SubCategories") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.flatMap { subCategory =>
          subCategory.asJsObject.getFields("SubCategoryName", "SubCategoryCName", "Groups") match {
            case Seq(JsString(country), JsString(countryId), JsArray(cLeagues)) =>
              cLeagues.map { group =>
                group.asJsObject.getFields("GroupName", "GroupCName") match {
                  case Seq(JsString(league), JsString(leagueId)) => League(-1, leagueId, "", league.toUpperCase, -1, countryId, "", country.toUpperCase)
                  case _                                         =>
                    log.error("One or more expected fields are missing into " + group + " json string. Expected fields: [GroupName, GroupCName]")
                    League.empty
                }
              }
            case _               =>
              log.error("One or more expected fields are missing into " + subCategory + " json string. Expected fields: [SubCategoryName, SubCategoryCName, Groups]")
              Vector.empty
          }
        }
      case _                    =>
        log.error("Field SubCategories is missing into " + json + " json string.")
        Vector.empty
    }

    // TODO: filter 'Italia' will be removed in next release
    leagues.filter(league => league.countryNameSite.equals("ITALIA"))
  }

}
