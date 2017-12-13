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

    val igFractions = (1 to 20).map(_.toDouble / 20)
    val freqThresholds = 1 to 20
    val portion = 1.0

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
      fraction <- igFractions.par
      threshold <- freqThresholds.par
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
        useCaptions= params.useCaptions,
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
      val highConfPercent = config.getDouble(s"classifiers.$dataset.highConfPercent")

      val (predictions, avgWeights, falsePos, falseNeg) =
        oc.binaryCV(
          accts,
          lbls,
          partitions,
          fraction,
          threshold,
          portion,
          followers,
          followees,
          Utils.svmFactory,
          labelSet,
          percentTopToConsider=highConfPercent
        )

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

      // Write analysis only on full portion
      if (portion == 1.0) {
        if (params.fpnAnalysis) {
          // Perform analysis on false negatives and false positives
          outputAnalysis(outputDir, avgWeights, falsePos, falseNeg)
        }

        // Save results
        val writer = new BufferedWriter(new FileWriter(outputDir + "/analysisMetrics.txt", false))
        writer.write(s"Precision: $precision\n")
        writer.write(s"Recall: $recall\n")
        writer.write(s"F-measure (harmonic mean): ${fMeasure(precision, recall, 1)}\n")
        writer.write(s"F-measure (recall 5x): ${fMeasure(precision, recall, .2)}\n")
        writer.write(s"Macro average: $macroAvg\n")
        writer.write(s"Micro average: $microAvg\n")
        writer.close()

        // Save individual predictions for bootstrap significance
        val predWriter = new BufferedWriter(new FileWriter(outputDir + "/predicted.txt", false))
        predWriter.write(s"gold\tpred\n")
        predictions.foreach(acct => predWriter.write(s"${acct._1}\t${acct._2}\n"))
        predWriter.close()
      }

      (fraction, threshold, precision, recall, macroAvg, microAvg)
    }

    println(s"\n$fileExt\nfraction\tthreshold\tp\tr\tf1\tmacro\tmicro")
    evals.seq.sorted.foreach{ case (fraction, threshold, precision, recall, macroAvg, microAvg) =>
      val f1 = fMeasure(precision, recall, 1)
      println(f"$fraction\t$threshold\t$precision%1.5f\t$recall%1.5f\t$f1%1.5f\t$macroAvg%1.5f\t$microAvg%1.5f")
    }
  }
}