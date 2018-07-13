/* Copyright 2017-present The KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package transitionbased

import com.kotlinnlp.neuralparser.language.Sentence
import com.kotlinnlp.neuralparser.language.CorpusDictionary
import com.kotlinnlp.neuralparser.parsers.transitionbased.templates.parsers.birnn.simple.BiRNNParserTrainer
import com.kotlinnlp.neuralparser.helpers.Validator
import com.kotlinnlp.neuralparser.parsers.transitionbased.models.ScorerNetworkConfiguration
import com.kotlinnlp.neuralparser.parsers.transitionbased.models.arcstandard.td.BiRNNTDArcStandardParser
import com.kotlinnlp.neuralparser.parsers.transitionbased.models.arcstandard.td.BiRNNTDArcStandardParserModel
import com.kotlinnlp.neuralparser.utils.loadFromTreeBank
import com.kotlinnlp.simplednn.core.functionalities.activations.Tanh
import com.kotlinnlp.simplednn.core.layers.LayerType
import com.kotlinnlp.syntaxdecoder.modules.actionserrorssetter.HingeLossActionsErrorsSetter
import com.kotlinnlp.syntaxdecoder.transitionsystem.models.arcstandard.ArcStandardOracle
import com.kotlinnlp.syntaxdecoder.transitionsystem.state.scoreaccumulator.AverageAccumulator

/**
 * Train a [BiRNNTDArcStandardParser].
 *
 * Command line arguments:
 *  1. The number of training epochs
 *  2. The file path of the training set
 *  3. The file path of the validation set
 *  4. The file path of the model
 */
fun main(args: Array<String>) {

  val epochs: Int = args[0].toInt()
  val trainingSetPath: String = args[1]
  val validationSetPath: String = args[2]
  val modelFilename: String = args[3]

  println("Loading training sentences...")
  val trainingSentences = ArrayList<Sentence>()
  trainingSentences.loadFromTreeBank(trainingSetPath, skipNonProjective = true)

  println("Creating corpus dictionary...")
  val corpusDictionary = CorpusDictionary(sentences = trainingSentences)

  val parserModel = BiRNNTDArcStandardParserModel(
    scoreAccumulatorFactory = AverageAccumulator.Factory,
    corpusDictionary = corpusDictionary,
    wordEmbeddingSize = 100,
    posEmbeddingSize = 25,
    biRNNConnectionType = LayerType.Connection.RAN,
    biRNNHiddenActivation = Tanh(),
    biRNNLayers = 2,
    scorerNetworkConfig = ScorerNetworkConfiguration(
      hiddenSize = 100,
      hiddenActivation = Tanh(),
      outputActivation = null))

  val parser = BiRNNTDArcStandardParser(
    model = parserModel,
    wordDropoutCoefficient = 0.25,
    posDropoutCoefficient = 0.0)

  val trainer = BiRNNParserTrainer(
    neuralParser = parser,
    actionsErrorsSetter = HingeLossActionsErrorsSetter(learningMarginThreshold = 1.0),
    oracleFactory = ArcStandardOracle,
    epochs = epochs,
    batchSize = 1,
    minRelevantErrorsCountToUpdate = 50,
    validator = Validator(
      neuralParser = parser,
      goldFilePath = validationSetPath),
    modelFilename = modelFilename)

  println("\n-- START TRAINING ON %d SENTENCES".format(trainingSentences.size))

  trainer.train(trainingSentences)
}