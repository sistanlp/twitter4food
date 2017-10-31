package org.clulab.twitter4food.t2dm

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}

import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory
import org.clulab.twitter4food.featureclassifier.ClassifierImpl
import org.clulab.twitter4food.util.{Eval, FileUtils, Utils}

/**
  * A classifier for classifying a TwitterAccount as "Overweight" or "Not overweight".
  *
  * @author terron
  * @author Dane Bell
  */
class OverweightClassifier(
  useUnigrams: Boolean = false,
  useBigrams: Boolean = false,
  useName: Boolean = false,
  useTopics: Boolean = false,
  useDictionaries: Boolean = false,
  useAvgEmbeddings: Boolean = false,
  useMinEmbeddings: Boolean = false,
  useMaxEmbeddings: Boolean = false,
  useCosineSim: Boolean = false,
  useLocation: Boolean = false,
  useTimeDate: Boolean = false,
  useFoodPerc: Boolean = false,
  useCaptions: Boolean = false,
  useFollowers: Boolean = false,
  useFollowees: Boolean = false,
  useRT: Boolean = false,
  useGender: Boolean = false,
  useAge: Boolean = false,
  useRace: Boolean = false,
  useHuman: Boolean = false,
  dictOnly: Boolean = false,
  denoise: Boolean = false,
  datumScaling: Boolean = false,
  featureScaling: Boolean = false)
  extends ClassifierImpl(
    useUnigrams=useUnigrams,
    useBigrams=useBigrams,
    useName=useName,
    useTopics=useTopics,
    useDictionaries=useDictionaries,
    useAvgEmbeddings=useAvgEmbeddings,
    useMinEmbeddings=useMinEmbeddings,
    useMaxEmbeddings=useMaxEmbeddings,
    useCosineSim=useCosineSim,
    useLocation=useLocation,
    useTimeDate=useTimeDate,
    useFoodPerc=useFoodPerc,
    useCaptions=useCaptions,
    useFollowers=useFollowers,
    useFollowees=useFollowees,
    useRT=useRT,
    useGender=useGender,
    useAge=useAge,
    useRace=useRace,
    useHuman=useHuman,
    dictOnly=dictOnly,
    denoise=denoise,
    datumScaling=datumScaling,
    featureScaling=featureScaling,
    variable = "overweight") {
  val labels = Set("Overweight", "Not overweight")
}

object OverweightClassifier {
  import ClassifierImpl._

  val logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]) {
    // Parse args using standard Config
    val params = Utils.parseArgs(args)
    val config = ConfigFactory.load

    // List of features (not counting domain adaptation)
    // if these are all false, set default to true to use unigrams anyway
    val allFeatures = Seq(
      params.useUnigrams,
      params.useBigrams,
      params.useName,
      params.useTopics,
      params.useDictionaries,
      params.useAvgEmbeddings,
      params.useMinEmbeddings,
      params.useMaxEmbeddings,
      params.useCosineSim,
      params.useLocation,
      params.useTimeDate,
      params.useFoodPerc,
      params.useCaptions,
      params.useFollowees
    )
    val default = allFeatures.forall(!_) // true if all features are off

    val dataset = if (params.useDiabetesData) "ow2" else "overweight"

    val portions = Seq(1.0)
    val percents = (1 to 100).map(_.toDouble / 100)

    val nonFeatures = Seq("--analysis", "--test", "--learningCurve")
    // This model and results are specified by all input args that represent featuresets
    val fileExt = args.filterNot(nonFeatures.contains).sorted.mkString("").replace("-", "")

    val resultsDir = config.getString(s"classifiers.$dataset.results")
    if (!Files.exists(Paths.get(resultsDir))) {
      if (new File(resultsDir).mkdir()) logger.info(s"Created output directory $resultsDir")
      else logger.info(s"ERROR: failed to create output directory $resultsDir")
    }
    val outputDir = resultsDir + "/" + fileExt
    if (!Files.exists(Paths.get(outputDir))) {
      if (new File(outputDir).mkdir()) logger.info(s"Created output directory $outputDir")
      else logger.info(s"ERROR: failed to create output directory $outputDir")
    }

    val modelFile = s"${config.getString("overweight")}/model/$fileExt.dat"
    // Instantiate classifier after prompts in case followers are being used (file takes a long time to loadTwitterAccounts)

    val partitionFile = if (params.usProps)
      config.getString(s"classifiers.$dataset.usFolds")
    else
      config.getString(s"classifiers.$dataset.folds")

    val partitions = FileUtils.readFromCsv(partitionFile).map { user =>
      user(1).toLong -> user(0).toInt // id -> partition
    }.toMap

    logger.info("Loading Twitter accounts")
    val labeledAccts = FileUtils.loadTwitterAccounts(config.getString(s"classifiers.$dataset.data"))
      .toSeq
      .filter(_._1.tweets.nonEmpty)
      .filter{ case (acct, lbl) => partitions.contains(acct.id)}

    val followers = if(params.useFollowers) {
      logger.info("Loading follower accounts...")
      Option(ClassifierImpl.loadFollowers(labeledAccts.map(_._1)))
    } else None

    val followees = if(params.useFollowees) {
      logger.info("Loading followee accounts...")
      Option(ClassifierImpl.loadFollowees(labeledAccts.map(_._1), dataset))
    } else None

    val evals = for {
      portion <- portions
    } yield {
      val (accts, lbls) = labeledAccts.unzip

      val oc = new OverweightClassifier(
        useUnigrams = default || params.useUnigrams,
        useBigrams = params.useBigrams,
        useName = params.useName,
        useTopics = params.useTopics,
        useDictionaries = params.useDictionaries,
        useAvgEmbeddings = params.useAvgEmbeddings,
        useMinEmbeddings = params.useMinEmbeddings,
        useMaxEmbeddings = params.useMaxEmbeddings,
        useCosineSim = params.useCosineSim,
        useLocation = params.useLocation,
        useTimeDate = params.useTimeDate,
        useFoodPerc = params.useFoodPerc,
        useCaptions = params.useCaptions,
        useFollowers = params.useFollowers,
        useFollowees = params.useFollowees,
        useRT = params.useRT,
        useGender = params.useGender,
        // useRace = params.useRace,
        dictOnly = params.dictOnly,
        denoise = params.denoise,
        datumScaling = params.datumScaling,
        featureScaling = params.featureScaling)

      logger.info("Training classifier...")

      val labelSet = Map("pos" -> "Overweight", "neg" -> "Not overweight")

      val results =
        oc.binaryCV(
          accts,
          lbls,
          partitions,
          1.0,
          followers,
          followees,
          Utils.svmFactory,
          labelSet,
          percentTopToConsider = percents
        )

      for {
        (predictions, conf) <- results
      } yield {
        // Print results
        val (evalMeasures, microAvg, macroAvg) = Eval.evaluate(predictions)

        val evalMetric = if (evalMeasures.keySet contains "Overweight") {
          evalMeasures("Overweight")
        } else {
          logger.debug(s"Labels are {${evalMeasures.keys.mkString(", ")}}. Evaluating on ${evalMeasures.head._1}")
          evalMeasures.head._2
        }
        val precision = evalMetric.P
        val recall = evalMetric.R

        (conf, predictions.length, precision, recall, macroAvg, microAvg)
      }
    }

    println(s"\n$fileExt\nconf\t#accts\tp\tr\tf1\tf1(r*5)\tmacro\tmicro")
    evals.head.foreach { case (hc, numAccounts, precision, recall, macroAvg, microAvg) =>
      println(s"$hc\t$numAccounts\t$precision\t$recall\t${fMeasure(precision, recall, 1)}\t${fMeasure(precision, recall, .2)}" +
        s"\t$macroAvg\t$microAvg")
    }
  }
}