package org.clulab.twitter4food.util

import org.clulab.twitter4food.twitter4j._
import org.clulab.twitter4food.struct._
import com.typesafe.config.ConfigFactory
import scala.reflect.ClassTag
import scala.collection.mutable.ArrayBuffer
import edu.arizona.sista.learning.LiblinearClassifier

object TestUtils {
  def init(keyset: Int, isAppOnly: Boolean) = {
    (new TwitterAPI(keyset, isAppOnly), ConfigFactory.load())
  }

  def loadHandles(fileName: String) = {
    scala.io.Source.fromFile(fileName).getLines.toList
      .foldLeft(Map[String, String]())(
        (map, line) => map + (line.split("\t")(0) -> line.split("\t")(1)))
  }

  def splitHandles[T: ClassTag](keyset: Int, numWindows: Int, 
    collection: Map[T, String]): (Array[T], Array[String]) = {
    val window = collection.size/numWindows
    val subHandles = collection.keys.slice(keyset*window, (keyset+1)*window)
      .toArray
    val subLabels = subHandles.map(k => collection(k))

    (subHandles -> subLabels)
  }

  def fetchAccounts(api: TwitterAPI, handles: Seq[String], 
    fetchTweets: Boolean) = {
    handles.map(h => api.fetchAccount(h, fetchTweets))
  }

  def parseArgs(args: Array[String]) = {
    case class Config(useUnigrams: Boolean = false, 
      useBigrams: Boolean = false,
      useTopics: Boolean = false,
      useDictionaries: Boolean = false,
      useEmbeddings: Boolean = false)

    val parser = new scopt.OptionParser[Config]("classifier") {
      head("classifier", "0.x")
      opt[Unit]('u', "unigrams") action { (x, c) =>
        c.copy(useUnigrams = true)} text("use unigrams")
      opt[Unit]('b', "bigrams") action { (x, c) =>
        c.copy(useBigrams = true)} text("use bigrams")
      opt[Unit]('t', "topics") action { (x, c) =>
        c.copy(useTopics = true)} text("use topics")                
      opt[Unit]('d', "dictionaries") action { (x, c) =>
        c.copy(useDictionaries = true)} text("use dictionaries")
      opt[Unit]('e', "embeddings") action { (x, c) =>
        c.copy(useEmbeddings = true)} text("use embeddings")
    }

    parser.parse(args, Config()).get
  }

  def analyze(c: LiblinearClassifier[String, String], labels: Set[String],
    test: TwitterAccount, args: Array[String]): 
    (Map[String, Seq[(String, Double)]], Map[String, Seq[(String, Double)]]) = {
    val params = parseArgs(args)
    val featureExtractor = new FeatureExtractor(params.useUnigrams, 
      params.useBigrams, params.useTopics,  params.useDictionaries,
      params.useEmbeddings)
    val W = c.getWeights()
    val d = featureExtractor.mkDatum(test, "unknown")

    val topWeights = labels.foldLeft(Map[String, Seq[(String, Double)]]())(
      (map, l) => map + (l -> W.get(l).get.toSeq.sortWith(_._2 > _._2)))

    val dotProduct = labels.foldLeft(Map[String, Seq[(String, Double)]]())(
      (map, l) => {
        val weightMap = W.get(l).get.toSeq.toMap
        val feats = d.featuresCounter.toSeq
        map + (l -> feats.filter(f => weightMap.contains(f._1))
          .map(f => (f._1, f._2 * weightMap(f._1))).sortWith(_._2 > _._2))
        })

    (topWeights, dotProduct)
  }

  def analyze(filename: String, labels: Set[String], test: TwitterAccount,
    args: Array[String]): 
    (Map[String, Seq[(String, Double)]], Map[String, Seq[(String, Double)]]) = {
    analyze(LiblinearClassifier.loadFrom[String, String](filename), labels,
      test, args)
  }
}