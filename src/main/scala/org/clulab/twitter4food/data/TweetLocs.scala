package org.clulab.twitter4food.data


import com.typesafe.config.{Config, ConfigFactory}
import org.clulab.twitter4food.twitter4j.TwitterAPI
import org.clulab.twitter4food.util.FileUtils._
import org.clulab.twitter4food.struct.Location
import org.slf4j.{Logger, LoggerFactory}

/**
  * Searches through existing accounts for geotagged tweets and prints out the user id, tweet id, latitude, and
  * longitude, one line per tweet. Takes a long time per account, but < 1% of tweets are geotagged.
  */
object TweetLocs extends App {
  case class PartialLocation(createdAt: java.util.Date)

  def retrieveCoords(ids: Seq[Long]): Seq[Location] = {
    val numProcesses = 18
    val chunkSize = ids.length / numProcesses

    val pics = for {
      thread <- (0 until numProcesses).par
      api = new TwitterAPI(thread)
      i <- thread * chunkSize until (thread + 1) * chunkSize
    } yield {
      logger.debug(s"fetching ${ids(i)}")
      api.fetchCoords(ids(i))
    }

    pics.seq.flatten
  }

  val config: Config = ConfigFactory.load
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val locFile = config.getString("classifiers.overweight.tweetCoords")

  // read account IDs in (all else is thrown away, not very efficient)
  val accts = loadTwitterAccounts(config.getString("classifiers.overweight.data"))
    .keys
    .filter(_.tweets.nonEmpty)
    .map(_.id)
    .toSeq

  // get the latitude and longitude using the Twitter API (takes a long time b/c API limits)
  val coords = retrieveCoords(accts)

  saveLocations(coords, locFile)
}