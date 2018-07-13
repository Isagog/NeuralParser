/* Copyright 2018-present LHRParser Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.lhrparser

import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.contextencoder.ContextEncoder
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.contextencoder.ContextEncoderOptimizer
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.headsencoder.HeadsEncoder
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.headsencoder.HeadsEncoderOptimizer
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.labeler.DeprelLabeler
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.labeler.DeprelLabelerOptimizer
import com.kotlinnlp.dependencytree.DependencyTree
import com.kotlinnlp.linguisticdescription.sentence.token.properties.Position
import com.kotlinnlp.neuralparser.helpers.Trainer
import com.kotlinnlp.neuralparser.helpers.Validator
import com.kotlinnlp.neuralparser.language.Sentence
import com.kotlinnlp.simplednn.core.functionalities.losses.MSECalculator
import com.kotlinnlp.simplednn.core.functionalities.updatemethods.UpdateMethod
import com.kotlinnlp.simplednn.core.functionalities.updatemethods.adam.ADAMMethod
import com.kotlinnlp.simplednn.simplemath.assignSum
import com.kotlinnlp.simplednn.simplemath.ndarray.Shape
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArray
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArrayFactory
import com.kotlinnlp.simplednn.utils.scheduling.BatchScheduling
import com.kotlinnlp.simplednn.utils.scheduling.EpochScheduling
import com.kotlinnlp.simplednn.utils.scheduling.ExampleScheduling
import com.kotlinnlp.tokensencoder.*

/**
 * The training helper.
 *
 * @param parser a neural parser
 * @param batchSize the size of the batches of sentences
 * @param epochs the number of training epochs
 * @param validator the validation helper (if it is null no validation is done after each epoch)
 * @param modelFilename the name of the file in which to save the best trained model
 * @param updateMethod the update method shared to all the parameters of the parser (Learning Rate, ADAM, AdaGrad, ...)
 * @param lhrErrorsOptions the settings for calculating the latent heads errors
 * @param verbose a Boolean indicating if the verbose mode is enabled (default = true)
 */
class LHRTrainer(
  private val parser: LHRParser,
  private val batchSize: Int,
  private val epochs: Int,
  validator: Validator?,
  modelFilename: String,
  private val updateMethod: UpdateMethod<*> = ADAMMethod(stepSize = 0.001, beta1 = 0.9, beta2 = 0.999),
  private val lhrErrorsOptions: LHRErrorsOptions,
  verbose: Boolean = true
) : Trainer(
  neuralParser = parser,
  batchSize = batchSize,
  epochs = epochs,
  validator = validator,
  modelFilename = modelFilename,
  minRelevantErrorsCountToUpdate = 1,
  verbose = verbose
) {

  /**
   * @property skipPunctuationErrors whether to do not consider punctuation errors
   */
  data class LHRErrorsOptions(val skipPunctuationErrors: Boolean)

  /**
   * The Encoder of the Latent Syntactic Structure.
   */
  private val lssEncoder = LSSEncoder(
    tokensEncoder = TokensEncoderFactory(this.parser.model.tokensEncoderModel, useDropout = true),
    contextEncoder = ContextEncoder(this.parser.model.contextEncoderModel, useDropout = true),
    headsEncoder = HeadsEncoder(this.parser.model.headsEncoderModel, useDropout = true),
    virtualRoot = this.parser.model.rootEmbedding.array.values)

  /**
   * The builder of the labeler.
   */
  private val deprelLabeler: DeprelLabeler? = this.parser.model.labelerModel?.let {
    DeprelLabeler(it, useDropout = true)
  }

  /**
   * The optimizer of the latent heads encoder.
   */
  private val headsEncoderOptimizer = HeadsEncoderOptimizer(
    model = this.parser.model.headsEncoderModel, updateMethod = this.updateMethod)

  /**
   * The optimizer of the context encoder.
   */
  private val contextEncoderOptimizer = ContextEncoderOptimizer(
    model = this.parser.model.contextEncoderModel, updateMethod = this.updateMethod)

  /**
   * The optimizer of the labeler (can be null).
   */
  private val deprelLabelerOptimizer: DeprelLabelerOptimizer? = this.parser.model.labelerModel?.let {
    DeprelLabelerOptimizer(model = this.parser.model.labelerModel, updateMethod = this.updateMethod)
  }

  /**
   * The optimizer of the tokens encoder.
   */
  private val tokensEncoderOptimizer = TokensEncoderOptimizerFactory(
    model = this.parser.model.tokensEncoderModel, updateMethod = this.updateMethod)

  /**
   * The epoch counter.
   */
  var epochCount: Int = 0

  /**
   * Group the optimizers all together.
   */
  private val optimizers = listOf(
    this.headsEncoderOptimizer,
    this.contextEncoderOptimizer,
    this.deprelLabelerOptimizer,
    this.tokensEncoderOptimizer)

  /**
   * @return a string representation of the configuration of this Trainer
   */
  override fun toString(): String = """
    %-33s : %s
    %-33s : %s
    %-33s : %s
  """.trimIndent().format(
    "Epochs", this.epochs,
    "Batch size", this.batchSize,
    "Skip punctuation errors", this.lhrErrorsOptions.skipPunctuationErrors
  )

  /**
   * Beat the occurrence of a new batch.
   */
  override fun newBatch() {

    if (this.updateMethod is BatchScheduling) {
      this.updateMethod.newBatch()
    }
  }

  /**
   * Beat the occurrence of a new epoch.
   */
  override fun newEpoch() {

    if (this.updateMethod is EpochScheduling) {
      this.updateMethod.newEpoch()
    }

    this.epochCount++
  }

  /**
   * Update the model parameters.
   */
  override fun update() {
    this.optimizers.forEach { it?.update() }
  }

  /**
   * @return the count of the relevant errors
   */
  override fun getRelevantErrorsCount(): Int = 1

  /**
   * Method to call before learning a new sentence.
   */
  private fun beforeSentenceLearning() {

    if (this.updateMethod is ExampleScheduling) {
      this.updateMethod.newExample()
    }
  }

  /**
   * Method to call after learning a sentence.
   */
  private fun afterSentenceLearning() {
    // TODO("Delete it?")
  }

  /**
   * Train the Transition System with the given [sentence].
   *
   * @param sentence a sentence
   */
  override fun trainSentence(sentence: Sentence) {

    val goldTree: DependencyTree = checkNotNull(sentence.dependencyTree) {
      "The gold dependency tree of a sentence cannot be null during the training."
    }

    val parsingSentence = ParsingSentence(tokens = (sentence.tokens.mapIndexed { index, token ->
      ParsingToken(index, token.word, position = Position(0, 0, 0), posTag = token.pos)
    }))

    this.beforeSentenceLearning()

    val lss: LatentSyntacticStructure = this.lssEncoder.encode(parsingSentence)
    val latentHeadsErrors = calculateLatentHeadsErrors(lss, goldTree.heads)

    val labelerErrors: List<DenseNDArray>? = this.deprelLabeler?.let {
      val labelerPrediction: List<DeprelLabeler.Prediction> = it.forward(DeprelLabeler.Input(lss, goldTree))
      this.parser.model.labelerModel?.calculateLoss(labelerPrediction, goldTree.deprels)
    }

    this.propagateErrors(
      latentHeadsErrors = latentHeadsErrors,
      labelerErrors = labelerErrors)

    this.afterSentenceLearning()
  }

  /**
   * Calculate the errors of the latent heads
   *
   * @param lss the latent syntactic structure
   * @param goldHeads the gold heads ids
   *
   * @return the errors of the latent heads
   */
  private fun calculateLatentHeadsErrors(lss: LatentSyntacticStructure, goldHeads: Array<Int?>): List<DenseNDArray> =
    MSECalculator().calculateErrors(
      outputSequence = lss.latentHeads,
      outputGoldSequence = this.getExpectedLatentHeads(lss, goldHeads))

  /**
   * Return a list containing the expected latent heads, one for each token of the sentence.
   *
   * @param lss the latent syntactic structure
   * @param goldHeads the gold heads ids
   *
   * @return the expected latent heads
   */
  private fun getExpectedLatentHeads(lss: LatentSyntacticStructure, goldHeads: Array<Int?>): List<DenseNDArray> {
    return lss.sentence.tokens.zip(goldHeads).map { (token, goldHeadId) ->
      when {
        goldHeadId == null -> lss.virtualRoot
        this.lhrErrorsOptions.skipPunctuationErrors && token.isComma -> lss.latentHeads[token.id] // no errors
        else -> lss.contextVectors[goldHeadId]
      }
    }
  }

  /**
   * Propagate the errors through the encoders.
   *
   * @param latentHeadsErrors the latent heads errors
   * @param labelerErrors the labeler errors
   */
  private fun propagateErrors(
    latentHeadsErrors: List<DenseNDArray>,
    labelerErrors: List<DenseNDArray>?){

    val contextErrors = List(size = latentHeadsErrors.size, init = {
      DenseNDArrayFactory.zeros(latentHeadsErrors[0].shape)
    } )

    val tokensErrors = List(size = latentHeadsErrors.size, init = {
      DenseNDArrayFactory.zeros(Shape(this.parser.model.tokensEncoderModel.tokenEncodingSize))
    } )

    contextErrors.assignSum(this.lssEncoder.headsEncoder.propagateErrors(latentHeadsErrors, this.headsEncoderOptimizer))

    this.deprelLabeler?.propagateErrors(labelerErrors!!, this.deprelLabelerOptimizer!!)?.let { labelerInputErrors ->
      contextErrors.assignSum(labelerInputErrors.contextErrors)
      this.propagateRootErrors(labelerInputErrors.rootErrors)
    }

    tokensErrors.assignSum(this.lssEncoder.contextEncoder.propagateErrors(contextErrors, this.contextEncoderOptimizer))

    this.lssEncoder.tokensEncoder.propagateErrors(tokensErrors, this.tokensEncoderOptimizer)
  }

  /**
   * Propagate the [errors] through the virtual root embedding.
   *
   * @param errors the errors
   */
  private fun propagateRootErrors(errors: DenseNDArray) {
    this.updateMethod.update(array = this.parser.model.rootEmbedding.array, errors = errors)
  }
}