package org.clulab.twitter4food.featureclassifier

import edu.arizona.sista.learning._
import edu.arizona.sista.struct.Counter
import org.clulab.twitter4food.struct._
import org.clulab.twitter4food.util._
import java.io._

class GenderClassifier(
  useUnigrams: Boolean = true, useBigrams: Boolean = false,
  useTopics: Boolean = false,  useDictionaries: Boolean = false,
  useEmbeddings: Boolean = false) extends ClassifierImpl(useUnigrams,
    useBigrams, useTopics, useDictionaries, useEmbeddings)

object GenderClassifier {
  def main(args: Array[String]): Unit = {
    val params = TestUtils.parseArgs(args)
    val (api, config) = TestUtils.init(0, true)
    val gc = new GenderClassifier(params.useUnigrams, params.useBigrams,
      params.useTopics, params.useDictionaries, params.useEmbeddings)

    println("Loading training accounts...")
    val trainingData = FileUtils.load(config
      .getString("classifiers.gender.trainingData"))
    println("Loading dev accounts...")
    val devData = FileUtils.load(config
      .getString("classifiers.gender.devData"))
    println("Loading test accounts...")
    val testData = FileUtils.load(config
      .getString("classifiers.gender.testData"))

    val fileExt = args.mkString("")
    val tweetFolds = Array(10, 50, 100, 500, 1000, 2000, 5000)
    val cFolds = Array(0.001, 0.01, 0.1, 1, 10, 100, 1000)
    
    /*
     * (i)  Tune parameters 
     * (ii) Pick top-K tweets
     */

    val (trainUsers, devUsers, testUsers) = (trainingData.keys.toArray, 
      devData.keys.toArray, testData.keys.toArray)

    val (trainLabels, devLabels, testLabels) = (trainingData.values.toArray, 
      devData.values.toArray, testData.values.toArray)

    val writer = new BufferedWriter(new FileWriter(
      config.getString("classifier") + "/gender/opt" + 
      fileExt + ".txt",true))

    val gridCbyK = Array.ofDim[Double](7,7)

    /*println(s"training data, label size : ${trainUsers.size}, ${trainLabels.size}")
    println(s"dev data, label size : ${devUsers.size}, ${devLabels.size}")
    println(s"testing data, label size : ${testUsers.size}, ${testLabels.size}")

    println(s"${(trainingData ++ devData).size}, ${(trainLabels ++ devLabels).size}")
    */
    
    val runTest = (trainingSet: Seq[TwitterAccount], 
      trainingLabels: Seq[String], 
      testSet: Seq[TwitterAccount],
      testingLabels: Seq[String],
      _C: Double,
      K: Int) => {
      
      println(s"Training with C=${_C} and top-${K} tweets")

      gc.subClassifier = Some(new LinearSVMClassifier[String, String](C=_C))
      val customAccounts = trainingSet.map(t => {
          val numTweets = Math.min(K, t.tweets.size)
          new TwitterAccount(t.handle, t.id, t.name, t.lang, t.url, 
            t.location, t.description, t.tweets.slice(0, numTweets))
        })

      val opt = config.getString("classifiers.gender.model")
      val fout = s"${opt}/svm_${args.mkString("")}_${_C}_${K}.dat"

      // Train with top K tweets
      gc.train(customAccounts, trainingLabels)
      gc.subClassifier.get.saveTo(fout)

      val pb = new me.tongfei.progressbar.ProgressBar("runTest()", 100)
      pb.start()
      pb.maxHint(testSet.size.toInt)
      pb.setExtraMessage("Testing...")

      val predictedLabels = testSet.map(u => { pb.step(); gc.classify(u); })
      pb.stop()

      val (evalMeasures, microAvg, macroAvg) = Eval.evaluate(testingLabels, 
        predictedLabels, testSet)
        
      val df = new java.text.DecimalFormat("#.###")

      println(s"C=${_C}, #K=$K")
      println(evalMeasures.mkString("\n"))
      println(s"\nMacro avg F-1 : ${df.format(macroAvg)}")
      println(s"Micro avg F-1 : ${df.format(microAvg)}")
      writer.write(s"C=${_C}, #K=${K}\n")
      writer.write(evalMeasures.mkString("\n"))
      evalMeasures.keys.foreach(l => {
        writer.write(l + "\n" + "FP:\n")
        writer.write(s"${evalMeasures(l).FPAccounts.map(u => u.handle).mkString("\n")}\nFN:\n")
        writer.write(s"${evalMeasures(l).FNAccounts.map(u => u.handle).mkString("\n")}\n")
        })
      writer.write(s"\nMacro avg F-1 : ${df.format(macroAvg)}\n")
      writer.write(s"Micro avg F-1 : ${df.format(microAvg)}\n")
      writer.flush()

      microAvg
    }

    for(i <- cFolds.indices) {
      val _C = cFolds(i)       
      for(j <- tweetFolds.indices) {
        val K = tweetFolds(j) 
        gridCbyK(i)(j) = runTest(trainUsers, trainLabels, 
          devUsers, devLabels, _C, K)
      }
    }

    var (iMax, jMax) = (0,0)
    var max = Double.MinValue
    for(i <- gridCbyK.indices)
      for(j <- gridCbyK(i).indices)
        if(gridCbyK(i)(j) > max) {
          max = gridCbyK(i)(j)
          iMax = i; jMax = j
        }

    println(s"Best C = ${cFolds(iMax)}, Top K = ${tweetFolds(jMax)}")
    writer.write(s"Best C = ${cFolds(iMax)}, Top K = ${tweetFolds(jMax)}\n")
    println("Testing with test users")

    writer.write("*****Test*****\n")
    writer.flush()

    // Final run on test Set
    val newTrain = trainUsers ++ devUsers
    val newLabels = trainLabels ++ devLabels

    runTest(newTrain, newLabels, testUsers, testLabels, cFolds(iMax), 
      tweetFolds(jMax))

    println("*****Test complete*****")
    writer.write("*****Test complete*****\n")
    writer.flush()

    writer.close()
  }
}