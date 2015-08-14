package edu.arizona.sista.twitter4food

import edu.stanford.nlp.kbp.slotfilling.classify.{ThresholdedJointBayes, JointBayesRelationExtractor, MultiLabelDataset, TwoClassJointBayes}
import edu.stanford.nlp.ling.{RVFDatum => S_RVFDatum, Datum => S_Datum, BasicDatum => S_BasicDatum}
import edu.stanford.nlp.stats.{Counter => S_Counter, ClassicCounter}
import scala.collection.JavaConverters._
import edu.arizona.sista.struct.Counter

/**
 * Created by dfried on 5/28/15.
 */
trait FeatureModel
case object AtLeastOnce extends FeatureModel
case object LabelDependencies extends FeatureModel
case object Fractions extends FeatureModel

trait LocalDataFilter
case object AllFilter extends LocalDataFilter
case object SingleFilter extends LocalDataFilter
case object RedundancyFilter extends LocalDataFilter
case class LargeFilter(k: Int) extends LocalDataFilter

trait InferenceType
case object Stable extends InferenceType
case object Slow extends InferenceType

case class NullableCounter[F](counter: Counter[F], initializeToNull: Boolean = false)

case class MIML[L, F](group: Seq[NullableCounter[F]], labels: Set[L])

trait YClassificationType
case object LR extends YClassificationType
case class Thresholded(positiveClass: String, negativeClass: String, initialThreshold: Double = 0.5) extends YClassificationType
case class TwoClass(positiveClass: String, negativeClass: String) extends YClassificationType

class MIMLWrapper(modelPath: Option[String] = None, numberOfTrainEpochs: Int = 6, numberOfFolds: Int = 5, localFilter: LocalDataFilter = AllFilter, featureModel: FeatureModel = AtLeastOnce, inferenceType: InferenceType = Stable, trainY: Boolean = true, onlyLocalTraining: Boolean = false, realValued: Boolean = true, zSigma: Double = 1.0, ySigma: Double = 1.0, classificationType: YClassificationType = LR) {

  val counterToDatumFn = if (realValued)
      MIMLWrapper.counterToRVFDatum[String,String] _
    else
      MIMLWrapper.counterToBVFDatum[String,String] _

  val localFilterString = localFilter match {
      case AllFilter => "all"
      case SingleFilter => "single"
      case RedundancyFilter => "redundancy"
      case LargeFilter(k) => s"large$k"
    }

  val featureModelInt = featureModel match { case AtLeastOnce => 0; case LabelDependencies => 1; case Fractions => 2}

  val inferenceTypeString = inferenceType match {
      case Stable => "stable"
      case Slow => "slow"
    }

  // public TwoClassJointBayes(String initialModelPath, int numberOfTrainEpochs, int numberOfFolds, String localFilter, int featureModel, String inferenceType, boolean trainY, boolean onlyLocalTraining, boolean useRVF, double zSigma, double ySigma, String positiveClass, String negativeClass) {
  val jbre = classificationType match {
    case Thresholded(positiveClass, negativeClass, initialThreshold) => new ThresholdedJointBayes(modelPath.getOrElse(null), numberOfTrainEpochs, numberOfFolds, localFilterString, featureModelInt, inferenceTypeString, trainY, onlyLocalTraining, realValued, zSigma, initialThreshold, positiveClass, negativeClass)
    case LR =>  new JointBayesRelationExtractor(modelPath.getOrElse(null), numberOfTrainEpochs, numberOfFolds, localFilterString, featureModelInt, inferenceTypeString, trainY, onlyLocalTraining, realValued, zSigma, ySigma)
    case TwoClass(positiveClass, negativeClass) => new TwoClassJointBayes(modelPath.getOrElse(null),  numberOfTrainEpochs, numberOfFolds, localFilterString, featureModelInt, inferenceTypeString, trainY, onlyLocalTraining, realValued, zSigma, ySigma, positiveClass, negativeClass);
  }

  def train(groups: Seq[MIML[String, String]]) = {
    jbre.train(MIMLWrapper.makeMultiLabelDataset(groups, realValued), groups.map(_.group.map(_.initializeToNull).toArray).toArray)
  }

  def classifyGroup(group: Seq[Counter[String]]): Seq[(String, Double)] = {
    val counter = jbre.classifyMentions(group.map(counterToDatumFn).asJava)
    MIMLWrapper.sortCounterDescending(counter)
  }

  def classifyIndividual(individualFeatures: Counter[String]) = {
    val counter = jbre.classifyLocally(counterToDatumFn(individualFeatures))
    MIMLWrapper.sortCounterDescending(counter)
  }
}

object MIMLWrapper {
  def sortCounterDescending(counter: S_Counter[String]) = (for {
      f <- counter.keySet().asScala.toSeq
  } yield f -> counter.getCount(f)).sortBy(-_._2)

  def makeMultiLabelDataset[L, F](groups: Seq[MIML[L, F]], realValued: Boolean = true) = {
    val mld = new MultiLabelDataset[L, F]
    for (labelledGroup <- groups) {
      val datums: Seq[S_Datum[L, F]] = for {
        NullableCounter(counter, _) <- labelledGroup.group
        datum = if (realValued)
          counterToRVFDatum[L,F](counter)
        else
          counterToBVFDatum[L,F](counter)

      } yield (datum)
      mld.addDatum(labelledGroup.labels.asJava, Set[L]().asJava, datums.asJava)
    }
    mld
  }

  def convertToStanfordCounter[F](in: Counter[F]): S_Counter[F] = {
    val out = new ClassicCounter[F](in.size)
    for (f <- in.keySet) {
      out.setCount(f, in.getCount(f))
    }
    out
  }

  def counterToRVFDatum[L,F](in: Counter[F]): S_Datum[L, F] =
    new S_RVFDatum[L, F](convertToStanfordCounter(in))

  def counterToBVFDatum[L,F](in: Counter[F]): S_Datum[L, F] =
     new S_BasicDatum[L, F](in.keySet.asJava)
}
