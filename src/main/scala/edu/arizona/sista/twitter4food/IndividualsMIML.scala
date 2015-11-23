package edu.arizona.sista.twitter4food

import edu.arizona.sista.struct._
import java.io._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import edu.arizona.sista.utils.EvaluationStatistics
import edu.arizona.sista.utils.StringUtils

/**
 * Created by dfried on 5/6/15.
 */
class IndividualsMIML(parameters: ExperimentParameters, printWriter: PrintWriter = new java.io.PrintWriter(System.out), val onlyLocalTraining: Boolean = false, val zSigma: Double = 1.0, val ySigma: Double = 1.0, val thresholded: Boolean = false, val twoClassLR: Boolean = false, trainY: Boolean = true, featureModel: FeatureModel = Fractions)
  extends Experiment(parameters = parameters, printWriter = printWriter) {

  require(!(thresholded && twoClassLR), "cannot have thresholded and twoClassLR")

  def run(trainingCorpus: Seq[IndividualsTweets],
          testingCorpus: Seq[IndividualsTweets],
          stateLabels: Map[String, String],
          onlyFoodTweets: Boolean = false,
          realValued: Boolean = true,
          featureSerializationPath: Option[String] = None) = {

    val trainingTweets = trainingCorpus.map(it => if (onlyFoodTweets) filterFoodTweets(it.tweets) else it.tweets)
    val testingTweets = testingCorpus.map(it => if (onlyFoodTweets) filterFoodTweets(it.tweets) else it.tweets)

    val (trainingFeatures, filterFn) =  mkViewFeatures(parameters.lexicalParameters.ngramThreshold)(trainingTweets)
    val testingFeatures: Seq[Counter[String]] = mkViewFeatures(None)(testingTweets)._1.map(_.filter(p => filterFn(p._1)))

    //val (processedFeatures, processFeaturesFn, _) = processFeatures(trainingFeatures, Seq()) // don't need to pass labels since we're not doing MI selection

    //def binarize(counter: Counter[String]) = new Counter(counter.keySet)

    //val (processedFeatures, processFeaturesFn) = (trainingFeatures.map(binarize), binarize _)

    var zInitializers: Seq[Option[String]] = for {
      user: IndividualsTweets <- trainingCorpus
      // set initialZLabel to Some("1") to initialize with overweight,
      // Some("0") to initialize with not overweight,
      // Some(MIMLWrapper.nullZLabel) to initialize with null,
      // or None to use the label from the state
      initialZLabel: Option[String] = None
    } yield initialZLabel

    val stateMIMLs = for {
      (Some(state), group) <- (trainingFeatures, trainingCorpus, zInitializers).zipped.groupBy((_._2.state)).toSeq
      stateFeatures: Seq[InitializableCounter[String, String]] = for {
        (features, individual, zInitializer) <- group.toSeq
      } yield InitializableCounter(features, zInitializer)
      label = stateLabels(state)
    } yield MIML[String, String](stateFeatures, Set(label))

    val classificationType = if (twoClassLR) TwoClass("1", "0") else if (thresholded) Thresholded("1", "0", 0.5) else LR


    val miml = new MIMLWrapper(realValued = realValued, onlyLocalTraining = onlyLocalTraining, zSigma = zSigma, ySigma = ySigma, classificationType=classificationType, trainY=trainY, featureModel = featureModel)
    miml.train(stateMIMLs)

    val predictedLabels = for {
      features <- testingFeatures
      predictions = miml.classifyIndividual(features)
    } yield predictions.head._1

    printWriter.println(miml.jbre.zClassifiers(0).toBiggestWeightFeaturesString(true, 20, true))

    predictedLabels

  }

}

object IndividualsMIML {
  import IndividualsBaseline.labelledAccuracy

  def main(args: Array[String]) {
    import Experiment._

    val props = StringUtils.argsToProperties(args, verbose=true)

    val realValued = StringUtils.getBool(props, "realValued", true)

    val onlyLocalTraining = StringUtils.getBool(props, "onlyLocalTraining", false)

    val zSigma = StringUtils.getDouble(props, "zSigma", 1.0)
    val ySigma = StringUtils.getDouble(props, "ySigma", 1.0)

    val evaluateOnDev = StringUtils.getBoolOption(props, "evaluateOnDev").get

    val thresholded = StringUtils.getBool(props, "thresholded", false)

    val twoClassLR = StringUtils.getBool(props, "twoClassLR", false)

    val excludeUsersWithMoreThan = StringUtils.getIntOption(props, "excludeUsersWithMoreThan")

    val trainY = StringUtils.getBool(props, "trainY", true)

    val resultsOut = StringUtils.getStringOption(props, "resultsOut")

    // val initializeOrganizationsToNull = StringUtils.getBool(props, "initializeOrganizationsToNull", false)

    val featureModel = if (StringUtils.getBool(props, "binaryYFeatures", false)) AtLeastOnce else Fractions

    val organizationsFile = StringUtils.getStringOption(props, "organizationsFiles")

    require(! (thresholded && twoClassLR), "cannot have thresholded and twoClassLR both set to true")

    // Some(k) to remove the k states closest to the bin edges when binning numerical data into classification,
    // or None to use all states
    val removeMarginals: Option[Int] = None
    // how many classes should we bin the numerical data into for classification?
    val numClasses = 2

    println(s"heap size: ${Runtime.getRuntime.maxMemory / (1024 * 1024)}")

    val outFile = StringUtils.getStringOption(props, "outputFile")
    val pw: PrintWriter = outFile match {
        case Some(fileName) => new PrintWriter(new java.io.File(fileName))
        case None => new PrintWriter(System.out)
    }

    val resultsPw: Option[PrintWriter] = resultsOut.map(filename => new PrintWriter(new java.io.File(filename)))

    val individualsCorpus = new IndividualsCorpus("/data/nlp/corpora/twitter4food/foodSamples-20150501", "/data/nlp/corpora/twitter4food/foodSamples-20150501/annotations.csv", numToTake=Some(500), excludeUsersWithMoreThan=excludeUsersWithMoreThan, organizationsFile = organizationsFile)

    val stateLabels = Experiment.makeLabels(Datasets.stateBMIs, numClasses, removeMarginals).mapValues(_.toString)

    val trainingTweets: Seq[IndividualsTweets] = IndividualsBaseline.makeBaselineTraining(numClasses, removeMarginals)(individualsCorpus)
    val testingTweets: List[IndividualsTweets] = if (evaluateOnDev) individualsCorpus.devTweets else individualsCorpus.testingTweets

    pw.println(s"${trainingTweets.size} training tweets")
    pw.println(s"${testingTweets.size} testing tweets")

    // create many possible variants of the experiment parameters, and for each map to results of running the
    // experiment
    // notation: = assigns a parameter to a single value
    //           <- means the parameter will take on all of the values in the list in turn
    val predictionsAndWeights = (for {
    // which base tokens to use? e.g. food words, hashtags, all words
      // tokenTypes: TokenType <- List(AllTokens, HashtagTokens, FoodTokens, FoodHashtagTokens).par
      tokenTypes: TokenType <- List(AllTokens).par
      // which annotators to use in addition to tokens?
      annotators <- List(
        //List(LDAAnnotator(tokenTypes), SentimentAnnotator),
        //List(SentimentAnnotator),
        // List(LDAAnnotator(tokenTypes)),
        List())
      // type of normalization to perform: normalize across a feature, across a state, or not at all
      // this has been supplanted by our normalization by the number of tweets for each state
      normalization = NoNorm
      // only keep ngrams occurring this many times or more
      ngramThreshold = Some(3)
      // split feature values into this number of quantiles
      numFeatureBins = None
      // use a bias in the SVM?
      useBias = false
      // use regions as features?
      regionType = NoRegions

      // Some(k) to use k classifiers bagged, or None to not do bagging
      baggingNClassifiers <- List(None)
      // force use of features that we think will be informative in random forests?
      forceFeatures = false
      // Some(k) to keep k features ranked by mutual information, or None to not do this
      miNumToKeep: Option[Int] = None
      // Some(k) to limit random forest tree depth to k levels, or None to not do this
      maxTreeDepth: Option[Int] = Some(3)
      // these were from failed experiments to use NNMF to reduce the feature space
      //reduceLexicalK: Option[Int] = None
      //reduceLdaK: Option[Int] = None

      filterFoodTweets = true

      params = new ExperimentParameters(new LexicalParameters(tokenTypes, annotators, normalization, ngramThreshold, numFeatureBins),
        classifierType=SVM_L2, // note: this is ignored
        useBias, regionType, baggingNClassifiers, forceFeatures, numClasses,
        miNumToKeep, maxTreeDepth, removeMarginals, featureScalingFactor = Some(1.0))
    } yield params -> new IndividualsMIML(params, pw, onlyLocalTraining = onlyLocalTraining, zSigma = zSigma, ySigma = ySigma, thresholded=thresholded, twoClassLR=twoClassLR, trainY=trainY, featureModel=featureModel).run(trainingTweets, testingTweets, stateLabels, filterFoodTweets, realValued = realValued)).seq

    for ((params, predictions) <- predictionsAndWeights.sortBy(_._1.toString)) {
      pw.println(params)

      val actual = testingTweets.map(_.label.get)
      val intPredictions = predictions.map(_.toInt)

      // baseline
      val majorityBaseline: Seq[Int] = predictMajorityNoCV(actual)
      val labelledBaseline = testingTweets zip majorityBaseline

      val (baselineCorrect, baselineTotal) = labelledAccuracy(labelledBaseline)
      pw.println(s"baseline overall accuracy\t${baselineCorrect} / ${baselineTotal}\t${baselineCorrect.toDouble / baselineTotal * 100.0}%")
      pw.println

      // system
      val labelledInstances: Seq[(IndividualsTweets, Int)] = testingTweets zip intPredictions
      val (correct, total) = labelledAccuracy(labelledInstances)
      val pvalue = EvaluationStatistics.classificationAccuracySignificance(intPredictions, majorityBaseline, actual)
      pw.println(s"system overall accuracy\t${correct} / ${total}\t${correct.toDouble / total * 100.0}%\t(p = ${pvalue})")
      pw.println

      val byClass: Map[Int, Seq[(IndividualsTweets, Int)]] = labelledInstances.groupBy(_._1.label.get)

      val byClassAccuracy = byClass.mapValues(labelledAccuracy).toMap
      // print the per-class accuracy
      pw.println("accuracy by class")
      for ((class_, (correct, total)) <- byClassAccuracy) {
        pw.println(s"class ${class_}\t${correct} / ${total}\t${correct.toDouble / total * 100.0}%")
      }
      pw.println

      def printPredictions(printWriter: PrintWriter): Unit = {
        // print each prediction
        printWriter.println("username,actual,predicted")
        for ((tweets, prediction) <- labelledInstances.sortBy( { case (it, prediction) => it.username } )) {
          printWriter.println(s"${tweets.username},${tweets.label.get},${prediction}")
        }

        pw.println
        pw.println
      }

      printPredictions(pw)
      resultsPw.foreach(printPredictions)

      }

    pw.println
    pw.println

    if (outFile != null) {
      try {
      } finally { pw.close() }
    } else {
      pw.flush()
    }

    resultsPw.foreach(_.close)
  }

  val organizationRegex =
  "cuisine|blog|bloggers|dietitian|bakery|food|kitchen|marketing|cafe|fit|eats|table|podcast|training|eatery|lunch|dinner|chef|restaurant".r

  def usernameIsOrganization(username: String): Boolean =
    organizationRegex.findFirstIn(username.toLowerCase).isDefined

}