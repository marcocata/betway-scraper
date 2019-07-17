package com.betway

import com.betway.Main.logger
import com.betway.domain.Market
import com.google.gson.{JsonElement, JsonObject, JsonParser}
import com.surebetfinder.config.Postgres
import com.surebetfinder.domain._
import com.surebetfinder.utils.{BashUtils, TimeUtils}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class Scanner(postgres: Postgres) {

  private val OS: Os = BashUtils.getOs
  private val URL = "https://sports.betway.it"

  /**
    * Retrieve all leagues for category 'soccer'
    * @return a List of League objects
    */
  def getLeagues(sport: String): List[League] = {
    val dataBinary = OS match {
      case Windows  => """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,\"CategoryCName\":\"""".stripMargin + sport + """\",\"ApplicationId\":5,\"TerritoryId\":110,\"ViewName\":\"sports\"}"""
      case _        => "{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,\"CategoryCName\":\"" + sport + "\",\"ApplicationId\":5,\"TerritoryId\":110,\"ViewName\":\"sports\"}"
    }

    val cmd = Seq(
      "curl", URL + "/api/Events/V2/GetCategoryDetails",
      "-H", "Origin: https://sports.betway.it",
      "-H", "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
      "-H", "Content-Type: application/json; charset=UTF-8",
      "-H", "Accept: application/json; charset=UTF-8",
      "-H", "Referer: https://sports.betway.it/it/sports/cat/" + sport,
      "--data-binary", dataBinary
    )

    Try(BashUtils.executeCmd(cmd)) match {
      case Success(cmdResult) => extractLeagues(cmdResult, sport)
      case Failure(fail) =>
        logger.error("Some error has occurred while executing " + cmd.mkString + " command!")
        fail.printStackTrace()
        List.empty
    }
  }

  /**
    * Extracts leagues from HTTP response, then compose a List of League objects
    *
    * @param cmdOutput, the HTTP response
    * @return a List of League objects
    */
  private def extractLeagues(cmdOutput: String, sport: String): List[League] = {
    val countriesJson = try {
      val jsonObj = new JsonParser().parse(cmdOutput).getAsJsonObject
      jsonObj.getAsJsonArray("SubCategories").asScala.map(country => country.getAsJsonObject)
    } catch {
      case ex: Exception =>
        logger.error("Some error has occurred while extracting 'Calcio' leagues from " + cmdOutput + " JSON string!")
        ex.printStackTrace()
        List.empty
    }

    val leagues = if(countriesJson.nonEmpty) {
      countriesJson.flatMap { country =>
        val countryName = extractNameId(country, "SubCategoryName")
        val countryId = extractNameId(country, "SubCategoryCName")
        val leaguesArray = extractCountryLeagues(country)

        leaguesArray.map { league =>
          val leagueObj = league.getAsJsonObject
          val leagueName = countryName + "-" + extractNameId(leagueObj, "GroupName")
          val leagueId = extractNameId(leagueObj, "GroupCName")

          League(
            sport match {
              case "soccer" => postgres.leaguesFootball.getOrElse(leagueName, League.empty).leagueIdPostgres
              case "tennis" => postgres.leaguesTennis.getOrElse(leagueName, League.empty).leagueIdPostgres
            },
            leagueId,
            "",
            leagueName,
            -1,
            countryId,
            "",
            countryName,
            postgres.bookmakers.getOrElse(Main.configuration.getSiteName, Bookmaker.empty).id,
            Main.configuration.getSiteName(),
            sport,
            sport match {
              case "soccer" => Soccer
              case "tennis" => Tennis
            }
          )
        }
      }
    } else List.empty

    val leaguesFiltered = leagues
    logger.info("Retrieved " + leaguesFiltered.size + " valid leagues out of a total of " + leagues.size + ".")
    leaguesFiltered.toList
  }

  /**
    * Extract events for passed league, then insert odds into postgres DB
    * @param league     , League object used to extracts odds
    */
  def getEvents(league: League): Unit = {
    val dataBinary = OS match {
      case Windows  => """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1, \"CategoryCName\":\"""".stripMargin + league.sport + """\",\"SubCategoryCName\":\"""".stripMargin + league.countryIdSite + """\",\"GroupCName\":\"""" + league.leagueIdSite + """\"}"""
      case _        => "{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1, \"CategoryCName\":\"".stripMargin + league.sport + "\",\"SubCategoryCName\":\"".stripMargin + league.countryIdSite + "\",\"GroupCName\":\"" + league.leagueIdSite + "\"}"
    }
    val cmd = Seq(
      "curl", "https://sports.betway.it/api/Events/V2/GetGroup",
      "-H", "Content-Type: application/json; charset=UTF-8",
      "--data-binary", dataBinary
    )

    Try(BashUtils.executeCmd(cmd)) match {
      case Success(cmdResult) =>
        val leagueEvents = extractEvents(cmdResult).mkString(",")

        // get league odds
        val marketName = league.sport match {
          case Soccer => "win-draw-win"
          case Tennis => "match-winner"
          case _        => ""
        }
        val dataBinary = OS match {
          case Windows  => """{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,\"ExternalIds\":[""" + leagueEvents + """],\"MarketCName\":\"""" + marketName + """\",\"ScoreboardRequest\":{\"ScoreboardType\":3,\"IncidentRequest\":{}},\"ApplicationId\":5,\"ViewName\":\"sports\"}"""
          case _        => "{\"LanguageId\":12,\"ClientTypeId\":2,\"BrandId\":3,\"JurisdictionId\":4,\"ClientIntegratorId\":1,\"ExternalIds\":[" + leagueEvents + "],\"MarketCName\":\"" + marketName + "\",\"ScoreboardRequest\":{\"ScoreboardType\":3,\"IncidentRequest\":{}},\"ApplicationId\":5,\"ViewName\":\"sports\"}"
        }
        val cmdEvents = Seq(
          "curl", "https://sports.betway.it/api/Events/V2/GetEvents",
          "-H", "Content-Type: application/json; charset=UTF-8",
          "--data-binary", dataBinary
        )

        Try(BashUtils.executeCmd(cmdEvents)) match {
          case Success(result)  =>
            val events = extractOdds(result, league)
            if(events.nonEmpty) {
              postgres.deleteLeagueEvents(league)
              postgres.insertIntoDB(league.sport, events)
            }

          case Failure(_)    =>
            logger.error("Some error has occurred while executing " + cmdEvents.mkString + " command!")
        }

      case Failure(_) =>
        logger.error("Some error has occurred while executing " + cmd.mkString + " command!")
    }
  }

  /**
    * Extract events from passed output bash command
    * @param cmdOutput  , the output of previous bash command
    * @return
    */
  private def extractEvents(cmdOutput: String): List[String] = {
    try {
      val jsonObj = new JsonParser().parse(cmdOutput).getAsJsonObject
      val categories = jsonObj.getAsJsonArray("Categories").asScala
      if(categories.nonEmpty) {
        categories.head.getAsJsonObject().getAsJsonArray("Events").asScala.map(event => event.getAsString).toList
      } else {
        logger.error("Field 'Categories' not found! "); List.empty
      }
    } catch {
      case ex: Exception =>
        logger.error("Some error has occurred while extracting 'Events' fields from " + cmdOutput + " JSON string!")
        ex.printStackTrace()
        List.empty
    }
  }

  /**
    * Extract odds from League object, then return a List of events
    * @param cmdOutput  , the output of previous bash command
    * @param league     , League object used to extracts odds
    * @return a List of Event objects
    */
  private def extractOdds(cmdOutput: String, league: League): List[Event] = {
    val teams = league.sport match {
      case Soccer => postgres.teamsFootball
      case Tennis => postgres.teamsTennis
    }

    val eventsJson = try {
      val jsonObj = new JsonParser().parse(cmdOutput).getAsJsonObject
      jsonObj.getAsJsonArray("Events").asScala.map(event => event.getAsJsonObject)
    } catch {
      case ex: Exception =>
        logger.error("Some error has occurred while extracting 'Events' fields from " + cmdOutput + " JSON string!")
        ex.printStackTrace()
        List.empty
    }
    val markets = extractMarkets(cmdOutput)
    val odds = extractOdds(cmdOutput)

    val eventsUpdated = eventsJson.map { event =>
      val homeName = extractNameId(event, "HomeTeamName")
      val awayName = extractNameId(event, "AwayTeamName")
      val eventTs = extractNameId(event, "Milliseconds").toLong / 1000
      val mkt = extractNameId(event, "CouponMarketId")

      if(homeName.nonEmpty && awayName.nonEmpty && mkt.nonEmpty) {
        Event(
          homeName,
          teams.getOrElse(homeName, Team.empty).id,
          awayName,
          teams.getOrElse(awayName, Team.empty).id,
          eventTs,
          -1,
          TimeUtils.isLive(eventTs),
          extractOdd(mkt, "1", markets, odds),
          if(league.sport.equals("soccer")) extractOdd(mkt, "X", markets, odds) else -1.0,
          extractOdd(mkt, "2", markets, odds),
          Main.configuration.getSiteName,
          postgres.bookmakers.getOrElse(Main.configuration.getSiteName, Bookmaker.empty).id,
          league.leagueNameSite,
          league.leagueIdPostgres
        )
      } else Event.empty
    }

    eventsUpdated.foreach(println)
    val eventsUpdatedFiltered = eventsUpdated.filter { e => e.homeId != -1 && e.awayId != -1 && e.bookmakerId != -1 && e.leagueId != -1 && !e.isLive && e.tsEvent != -1 && e.oddHome != -1.0 && e.oddAway != -1.0 }
    logger.info("Retrieved " + eventsUpdatedFiltered.size + " valid events for " + league.countryIdSite + "/" + league.leagueIdSite + " out of a total of " + eventsUpdated.size + " events.")
    eventsUpdatedFiltered.toList
  }

  private def extractNameId(element: JsonObject, fieldName: String): String = {
    if(!element.isJsonNull) {
      val countryName = element.get(fieldName)
      if(!countryName.isJsonNull) countryName.getAsString.trim.toUpperCase()
      else {
        logger.error("Element '" + fieldName + "' not found from " + element + " !"); ""
      }
    } else {
      logger.error("Parameter 'element' is null! "); ""
    }
  }

  private def extractCountryLeagues(element: JsonObject): Iterable[JsonElement] = {
    if(element != null) {
      val groups = element.getAsJsonArray("Groups").asScala
      if(groups.nonEmpty) groups
      else {
        logger.error("Element 'Groups' not found from " + element + " !"); List.empty
      }
    } else {
      logger.error("Parameter 'element' is null! "); List.empty
    }
  }

  private def extractMarkets(cmdOutput: String): Map[Int, List[Market]] = {
    println(cmdOutput)
    val marketsJson = try {
      val jsonObj = new JsonParser().parse(cmdOutput).getAsJsonObject
      jsonObj.getAsJsonArray("Markets").asScala.map(event => event.getAsJsonObject)
    } catch {
      case ex: Exception =>
        logger.error("Some error has occurred while extracting 'Markets' fields from " + cmdOutput + " JSON string!")
        ex.printStackTrace()
        List.empty
    }

    if(marketsJson.nonEmpty) {
      marketsJson.map { market =>
        val marketObj = market.getAsJsonObject()
        val id = marketObj.getAsJsonPrimitive("Id").getAsInt
        val title = marketObj.getAsJsonPrimitive("Title").getAsString
        val outcomes = marketObj.getAsJsonArray("Outcomes").asScala
        val osIDs = outcomes.head.getAsJsonArray().asScala.toList.map(el => el.getAsJsonPrimitive.getAsInt)
        Market(id, title, osIDs)
      }.toList.groupBy(g => g.id)
    } else {
      logger.error("No 'Markets' element found from " + marketsJson + " !"); Map.empty
    }
  }

  private def extractOdds(cmdOutput: String): Map[Int, Double] = {
    val outcomesJson = try {
      val jsonObj = new JsonParser().parse(cmdOutput).getAsJsonObject()
      jsonObj.getAsJsonArray("Outcomes").asScala.map(event => event.getAsJsonObject)
    } catch {
      case ex: Exception =>
        logger.error("Some error has occurred while extracting 'Outcomes' fields from " + cmdOutput + " JSON string!")
        ex.printStackTrace()
        List.empty
    }

    if(outcomesJson.nonEmpty) {
      outcomesJson.map { outcome =>
        val id = outcome.getAsJsonObject().getAsJsonPrimitive("Id").getAsInt
        val value = outcome.getAsJsonObject().getAsJsonPrimitive("OddsDecimalDisplay").getAsDouble
        id -> value
      }.toMap
    } else {
      logger.error("No 'Outcomes' element found from " + cmdOutput + " !"); Map.empty
    }
  }

  private def extractOdd(market: String, label: String, markets: Map[Int, List[Market]], odds: Map[Int, Double]): Double = {
    val marketList = markets.getOrElse(market.toInt, List.empty)
    if(marketList.nonEmpty) {
      val oddKey = label match {
        case "1" => marketList.head.outcomes.head
        case "X" => marketList.head.outcomes(1)
        case "2" => marketList.head.outcomes.last
      }
      odds.getOrElse(oddKey, -1.0)
    } else -1.0
  }

}
