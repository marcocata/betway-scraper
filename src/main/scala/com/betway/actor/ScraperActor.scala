package com.betway.actor

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.event.Logging
import com.betway.domain.{Market, Outcome}
import com.surebetfinder.config.SureBetFinderConfiguration
import com.surebetfinder.db.Postgres
import com.surebetfinder.domain.{Event, League, Team}
import com.surebetfinder.utils.BashUtils
import spray.json._
import com.surebetfinder.domain.Bookmaker

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

class ScraperActor extends Actor {

  private val log = Logging(context.system, this)
  private val configuration: SureBetFinderConfiguration = new SureBetFinderConfiguration().load("./betway.yml")

  def receive: PartialFunction[Any, Unit] = {
    case (league: League, postgres: Postgres) =>
      log.info(s"Getting odds of ${league.countryIdSite}/${league.leagueIdSite}...")
      val cmd = Seq(
        "curl",
        "https://sports.betway.it/api/Events/V2/GetGroup",
        "-H",
        "Content-Type: application/json; charset=UTF-8",
        "--data-binary",
        """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,
           \"CategoryCName\":\"soccer\",\"SubCategoryCName\":\"""".stripMargin + league.countryIdSite +
           """\",\"GroupCName\":\"""" + league.leagueIdSite + """\"}"""
      )

      Try(BashUtils.executeCmd(cmd)) match {
        case Success(cmdResult) =>
          val leagueEvents = extractEvents(cmdResult, league).mkString(",")

          // get league odds
          val cmdEvents = Seq(
            "curl",
            "https://sports.betway.it/api/Events/V2/GetEvents",
            "-H",
            "Content-Type: application/json; charset=UTF-8",
            "--data-binary",
            """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,
            \"ExternalIds\":[""" + leagueEvents + """],\"MarketCName\":\"win-draw-win\",\"ScoreboardRequest\":{\"ScoreboardType\":3
            ,\"IncidentRequest\":{}},\"ApplicationId\":5,\"ViewName\":\"sports\"}"""
          )

          Try(BashUtils.executeCmd(cmdEvents)) match {
            case Success(result)  =>
              val events = extractOdds(result, league, postgres)
              postgres.insertIntoDB(events)

            case Failure(fail)    =>
              log.error("Some error has occured while executing " + cmdEvents.mkString + " command!")
              log.error(fail.getMessage)
          }
          Await.ready(context.system.whenTerminated, Duration(2, TimeUnit.SECONDS))

        case Failure(fail) =>
          log.error("Some error has occured while executing " + cmd.mkString + " command!")
          log.error(fail.getMessage)
      }

    case _                => log.error("It was received an invalid parameter! Excepted: (League, Postgres)")
  }

  def extractEvents(message: String, league: League): Vector[String] = {
    val json = message.parseJson

    json.asJsObject.fields.get("Categories") match {
      case Some(categories) =>
        categories.asInstanceOf[JsArray].elements.head.asJsObject.getFields("Events") match {
          case Seq(JsArray(ev))  => ev.map(e => e.toString)
          case _                 =>
            log.error("Field Events is missing into " + categories + " json string.")
            Vector.empty
        }
      case _                                              =>
        log.error("Field Categories is missing into " + json + " json string.")
        Vector.empty
    }
  }

  def extractOdds(message: String, league: League, postgres: Postgres): Vector[Event] = {
    val json = message.parseJson.asJsObject

    // get all events for passed leagues
    val events = json.fields.get("Events") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.map { event =>
          event.asJsObject.getFields("HomeTeamName", "AwayTeamName", "Milliseconds", "CouponMarketId", "IsLive") match {
            case Seq(JsString(home), JsString(away), JsNumber(tsEvent), JsNumber(mkt), JsBoolean(isLive)) =>
              Event(home, -1, away, -1, tsEvent.toLong / 1000, mkt.toInt, isLive, -1.0, -1.0, -1.0, configuration.getSiteName, -1, "", -1)
            case _ =>
              log.error("One or more expected fields are missing into " + event + " json string. Expected fields: [HomeTeamName, AwayTeamName, Milliseconds, CouponMarketId, IsLive]")
              Event.empty
          }
        }.filter(e => !e.home.isEmpty)

      case _ =>
        log.error("Field 'Events' is missing into " + json + " json string.")
        Vector.empty
    }

    // get all markets for passed leagues
    val markets: Map[BigDecimal, Vector[Market]] = json.fields.get("Markets") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.map { market =>
          market.asJsObject.getFields("Id", "Title", "Outcomes") match {
            case Seq(JsNumber(id), JsString(title), JsArray(os)) =>
              val osIDs = os.map(m => m.toString).head
                .replace("[", "")
                .replace("]", "")
                .split(",")
                .map(_.toInt)
                .toList

              Market(id.toInt, title, osIDs)
            case _ =>
              log.error("One or more expected fields are missing into " + market + " json string. Expected fields: [Id, Title, Outcomes]")
              Market.empty
          }
        }.groupBy(g => g.id)

      case _ =>
        log.error("Field 'Markets' is missing into " + json + " json string.")
        Map.empty
    }

    // get all outcomes
    val outcomes: Map[BigDecimal, Vector[Outcome]] = json.fields.get("Outcomes") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.map { outcome =>
          outcome.asJsObject.getFields("Id", "OddsDecimalDisplay") match {
            case Seq(JsNumber(id), JsString(odd)) => Outcome(id.toInt, odd.toDouble)
            case _                                =>
              log.error("One or more expected fields are missing into " + outcome + " json string. Expected fields: [Id, OddsDecimalDisplay]")
              Outcome.empty
          }
        }.groupBy(g => g.id)

      case _ =>
        log.error("Field 'Outcomes' is missing into " + json + " json string.")
        Map.empty
    }

    val eventsUpdated = events
      .filter { e => !e.isLive }
      .map { e =>
        e.copy(
          homeId        = postgres.teams.getOrElse(e.home.toUpperCase, Team.empty).id,
          awayId        = postgres.teams.getOrElse(e.away.toUpperCase, Team.empty).id,
          oddHome       = outcomes(markets(e.market)(0).outcomes.head)(0).value,
          oddDraw       = outcomes(markets(e.market)(0).outcomes(1))(0).value,
          oddAway       = outcomes(markets(e.market)(0).outcomes(2))(0).value,
          bookmakerId   = postgres.bookmakers.getOrElse(e.bookmaker.toUpperCase, Bookmaker.empty).id,
          league        = league.leagueNameSite,
          leagueId      = postgres.leagues.getOrElse(league.leagueNameSite.toUpperCase, League.empty).leagueIdPostgres,
        )
      }

    val eventsUpdatedFiltered = eventsUpdated.filter { e => e.homeId != -1 && e.awayId != -1 && e.bookmakerId != -1 && e.leagueId != -1}
    log.info("Retrieved " + eventsUpdatedFiltered.size + " valid events for " + league.countryIdSite + "/" + league.leagueIdSite + " out of a total of " + eventsUpdated.size + " events.")
    eventsUpdatedFiltered
  }

}
