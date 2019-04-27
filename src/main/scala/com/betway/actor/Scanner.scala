package com.betway.actor

import com.betway.Main
import com.surebetfinder.domain.{Bookmaker, Event, League, Team}
import com.surebetfinder.utils.BashUtils
import com.betway.Main.logger
import com.betway.domain.{Market, Outcome}
import com.surebetfinder.db.Postgres
import spray.json._
import scala.util.{Failure, Success, Try}

object Scanner {

  /**
    * Retrieve all leagues for category 'soccer'
    * @return a Vector of League objects
    */
  def getLeagues: Vector[League] = {
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
        leagues

      case Failure(fail) =>
        logger.error("Some error has occured while executing " + cmd.mkString + " command!")
        fail.printStackTrace()
        Vector.empty
    }
  }

  /**
    * Extracts data from passed bash command output, then compose a Vector of League objects
    * @param cmdOutput, the output of previous bash command
    * @return a Vector of League objects
    */
  private def extractLeagues(cmdOutput: String): Vector[League] = {
    val json = cmdOutput.parseJson

    val leagues = json.asJsObject.fields.get("SubCategories") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.flatMap { subCategory =>
          subCategory.asJsObject.getFields("SubCategoryName", "SubCategoryCName", "Groups") match {
            case Seq(JsString(country), JsString(countryId), JsArray(cLeagues)) =>
              cLeagues.map { group =>
                group.asJsObject.getFields("GroupName", "GroupCName") match {
                  case Seq(JsString(league), JsString(leagueId)) => League(-1, leagueId, "", league.toUpperCase, -1, countryId, "", country.toUpperCase)
                  case _                                         =>
                    logger.error("One or more expected fields are missing into " + group + " json string. Expected fields: [GroupName, GroupCName]")
                    League.empty
                }
              }
            case _               =>
              logger.error("One or more expected fields are missing into " + subCategory + " json string. Expected fields: [SubCategoryName, SubCategoryCName, Groups]")
              Vector.empty
          }
        }
      case _                    =>
        logger.error("Field SubCategories is missing into " + json + " json string.")
        Vector.empty
    }

    // TODO: filter 'Italia' will be removed in next release
    leagues.filter(league => league.countryNameSite.equals("ITALIA"))
  }

  /**
    * Extract events for passed league, then insert odds into postgres DB
    * @param league     , League object used to extracts odds
    * @param postgres   , Postgres connection
    */
  def getEvents(league: League, postgres: Postgres): Unit = {
    logger.info(s"Getting odds of ${league.countryIdSite}/${league.leagueIdSite}...")
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
        val leagueEvents = extractEvents(cmdResult).mkString(",")

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
            logger.error("Some error has occured while executing " + cmdEvents.mkString + " command!")
            logger.error(fail.getMessage)
        }

      case Failure(fail) =>
        logger.error("Some error has occured while executing " + cmd.mkString + " command!")
        logger.error(fail.getMessage)
    }
  }

  /**
    * Extract single event from passed output bash command
    * @param cmdOutput  , the output of previous bash command
    * @return
    */
  private def extractEvents(cmdOutput: String): Vector[String] = {
    val json = cmdOutput.parseJson

    json.asJsObject.fields.get("Categories") match {
      case Some(categories) =>
        categories.asInstanceOf[JsArray].elements.head.asJsObject.getFields("Events") match {
          case Seq(JsArray(ev))  => ev.map(e => e.toString)
          case _                 =>
            logger.error("Field Events is missing into " + categories + " json string.")
            Vector.empty
        }
      case _                                              =>
        logger.error("Field Categories is missing into " + json + " json string.")
        Vector.empty
    }
  }

  /**
    * Extract odds from League object, then return a Vector of events
    * @param cmdOutput  , the output of previous bash command
    * @param league     , League object used to extracts odds
    * @param postgres   , Postgres connection
    * @return a Vector of Event objects
    */
  private def extractOdds(cmdOutput: String, league: League, postgres: Postgres): Vector[Event] = {
    val json = cmdOutput.parseJson.asJsObject

    // get all events for passed leagues
    val events = json.fields.get("Events") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.map { event =>
          event.asJsObject.getFields("HomeTeamName", "AwayTeamName", "Milliseconds", "CouponMarketId", "IsLive") match {
            case Seq(JsString(home), JsString(away), JsNumber(tsEvent), JsNumber(mkt), JsBoolean(isLive)) =>
              Event(home, -1, away, -1, tsEvent.toLong / 1000, mkt.toInt, isLive, -1.0, -1.0, -1.0, Main.configuration.getSiteName, -1, "", -1)
            case _ =>
              logger.error("One or more expected fields are missing into " + event + " json string. Expected fields: [HomeTeamName, AwayTeamName, Milliseconds, CouponMarketId, IsLive]")
              Event.empty
          }
        }.filter(e => !e.home.isEmpty)

      case _ =>
        logger.error("Field 'Events' is missing into " + json + " json string.")
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
              logger.error("One or more expected fields are missing into " + market + " json string. Expected fields: [Id, Title, Outcomes]")
              Market.empty
          }
        }.groupBy(g => g.id)

      case _ =>
        logger.error("Field 'Markets' is missing into " + json + " json string.")
        Map.empty
    }

    // get all outcomes
    val outcomes: Map[BigDecimal, Vector[Outcome]] = json.fields.get("Outcomes") match {
      case Some(list) =>
        list.asInstanceOf[JsArray].elements.map { outcome =>
          outcome.asJsObject.getFields("Id", "OddsDecimalDisplay") match {
            case Seq(JsNumber(id), JsString(odd)) => Outcome(id.toInt, odd.toDouble)
            case _                                =>
              logger.error("One or more expected fields are missing into " + outcome + " json string. Expected fields: [Id, OddsDecimalDisplay]")
              Outcome.empty
          }
        }.groupBy(g => g.id)

      case _ =>
        logger.error("Field 'Outcomes' is missing into " + json + " json string.")
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
    logger.info("Retrieved " + eventsUpdatedFiltered.size + " valid events for " + league.countryIdSite + "/" + league.leagueIdSite + " out of a total of " + eventsUpdated.size + " events.")
    eventsUpdatedFiltered
  }
}
