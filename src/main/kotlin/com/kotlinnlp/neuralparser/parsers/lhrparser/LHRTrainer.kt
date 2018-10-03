/* Copyright 2018-present KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.lhrparser

import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.labeler.Labeler
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.labeler.LabelerOptimizer
import com.kotlinnlp.dependencytree.DependencyTree
import com.kotlinnlp.lssencoder.LSSEncoder
import com.kotlinnlp.lssencoder.LSSOptimizer
import com.kotlinnlp.lssencoder.LatentSyntacticStructure
import com.kotlinnlp.neuralparser.helpers.preprocessors.SentencePreprocessor
import com.kotlinnlp.neuralparser.helpers.Trainer
import com.kotlinnlp.neuralparser.helpers.Validator
import com.kotlinnlp.neuralparser.helpers.preprocessors.BasePreprocessor
import com.kotlinnlp.neuralparser.language.ParsingSentence
import com.kotlinnlp.neuralparser.language.ParsingToken
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.PositionalEncoder
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.PositionalEncoder.Companion.calculateErrors
import com.kotlinnlp.simplednn.core.functionalities.losses.MSECalculator
import com.kotlinnlp.simplednn.core.functionalities.updatemethods.UpdateMethod
import com.kotlinnlp.simplednn.core.functionalities.updatemethods.adam.ADAMMethod
import com.kotlinnlp.simplednn.core.optimizer.Optimizer
import com.kotlinnlp.simplednn.core.optimizer.ParamsOptimizer
import com.kotlinnlp.simplednn.deeplearning.attention.pointernetwork.PointerNetworkProcessor
import com.kotlinnlp.simplednn.simplemath.assignSum
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArray
import com.kotlinnlp.simplednn.utils.scheduling.BatchScheduling
import com.kotlinnlp.simplednn.utils.scheduling.EpochScheduling
import com.kotlinnlp.simplednn.utils.scheduling.ExampleScheduling

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
 * @param sentencePreprocessor the sentence preprocessor (e.g. to perform morphological analysis)
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
  sentencePreprocessor: SentencePreprocessor = BasePreprocessor(),
  verbose: Boolean = true
) : Trainer(
  neuralParser = parser,
  batchSize = batchSize,
  epochs = epochs,
  validator = validator,
  modelFilename = modelFilename,
  minRelevantErrorsCountToUpdate = 1,
  sentencePreprocessor = sentencePreprocessor,
  verbose = verbose
) {

  /**
   * @property skipPunctuationErrors whether to do not consider punctuation errors
   */
  data class LHRErrorsOptions(
    val skipPunctuationErrors: Boolean,
    val usePositionalEncodingErrors: Boolean)

  /**
   * The Encoder of the Latent Syntactic Structure.
   */
  private val lssEncoder = LSSEncoder(model = this.parser.model.lssModel, useDropout = true)

  /**
   * The builder of the labeler.
   */
  private val labeler: Labeler? = this.parser.model.labelerModel?.let { Labeler(it, useDropout = true) }

  /**
   * The positional encoder.
   */
  private val positionalEncoder: PositionalEncoder? = if (this.lhrErrorsOptions.usePositionalEncodingErrors)
    PositionalEncoder(this.parser.model.pointerNetworkModel, useDropout = true)
  else
    null

  /**
   * The pointer network optimizer.
   */
  private val pointerNetworkOptimizer = ParamsOptimizer(
    params = this.parser.model.pointerNetworkModel.params, updateMethod = this.updateMethod)

  /**
   * The optimizer of the LSS encoder.
   */
  private val lssEncoderOptimizer = LSSOptimizer(model = this.parser.model.lssModel, updateMethod = this.updateMethod)

  /**
   * The optimizer of the labeler (can be null).
   */
  private val labelerOptimizer: LabelerOptimizer? = this.parser.model.labelerModel?.let {
    LabelerOptimizer(model = this.parser.model.labelerModel, updateMethod = this.updateMethod)
  }

  /**
   * The epoch counter.
   */
  private var epochCount: Int = 0

  /**
   * Group the optimizers all together.
   */
  private val optimizers: List<Optimizer<*>?> = listOf(
    this.lssEncoderOptimizer,
    this.labelerOptimizer,
    this.pointerNetworkOptimizer)

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
    if (this.updateMethod is BatchScheduling) this.updateMethod.newBatch()
  }

  /**
   * Beat the occurrence of a new epoch.
   */
  override fun newEpoch() {

    if (this.updateMethod is EpochScheduling) this.updateMethod.newEpoch()

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
    if (this.updateMethod is ExampleScheduling) this.updateMethod.newExample()
  }

  /**
   * Train the Transition System with the given [sentence] and [goldTree].
   *
   * @param sentence the sentence
   * @param goldTree the gold tree of the sentence
   */
  override fun trainSentence(sentence: ParsingSentence, goldTree: DependencyTree) {

    this.beforeSentenceLearning()

    val lss: LatentSyntacticStructure<ParsingToken, ParsingSentence> = this.lssEncoder.forward(sentence)
    val latentHeadsErrors = calculateLatentHeadsErrors(lss, goldTree)

    val labelerErrors: List<DenseNDArray>? = this.labeler?.let {
      val labelerPrediction: List<DenseNDArray> = it.forward(Labeler.Input(lss, goldTree))
      this.parser.model.labelerModel?.calculateLoss(labelerPrediction, goldTree)
    }

    val positionalEncoderErrors: PointerNetworkProcessor.InputErrors? = this.positionalEncoder?.let {
      it.propagateErrors(calculateErrors(it.forward(lss.contextVectors)), this.pointerNetworkOptimizer)
    }

    this.propagateErrors(
      latentHeadsErrors = latentHeadsErrors,
      labelerErrors = labelerErrors,
      positionalEncoderErrors = positionalEncoderErrors)
  }

  /**
   * Calculate the errors of the latent heads
   *
   * @param lss the latent syntactic structure
   * @param goldTree the gold tree of the sentence
   *
   * @return the errors of the latent heads
   */
  private fun calculateLatentHeadsErrors(lss: LatentSyntacticStructure<ParsingToken, ParsingSentence>,
                                         goldTree: DependencyTree): List<DenseNDArray> =
    MSECalculator().calculateErrors(
      outputSequence = lss.latentHeads,
      outputGoldSequence = this.getExpectedLatentHeads(lss, goldTree))

  /**
   * Return a list containing the expected latent heads, one for each token of the sentence.
   *
   * @param lss the latent syntactic structure
   * @param goldTree the gold tree of the sentence
   *
   * @return the expected latent heads
   */
  private fun getExpectedLatentHeads(lss: LatentSyntacticStructure<ParsingToken, ParsingSentence>,
                                     goldTree: DependencyTree): List<DenseNDArray> =

    lss.sentence.tokens.map { token ->

      val goldHeadId: Int? = goldTree.getHead(token.id)

      when {
        goldHeadId == null -> lss.virtualRoot
        this.lhrErrorsOptions.skipPunctuationErrors && token.isComma -> lss.getLatentHeadById(token.id) // no errors
        else -> lss.getContextVectorById(goldHeadId)
      }
    }

  /**
   * Propagate the errors through the encoders.
   *
   * @param latentHeadsErrors the latent heads errors
   * @param labelerErrors the labeler errors
   * @param positionalEncoderErrors the positional encoder errors
   */
  private fun propagateErrors(latentHeadsErrors: List<DenseNDArray>,
                              labelerErrors: List<DenseNDArray>?,
                              positionalEncoderErrors: PointerNetworkProcessor.InputErrors?) {

    val contextVectorsErrors: List<DenseNDArray> = latentHeadsErrors.map { it.zerosLike() }

    positionalEncoderErrors?.let { contextVectorsErrors.assignSum(it.inputVectorsErrors) }

    this.labeler?.propagateErrors(labelerErrors!!, this.labelerOptimizer!!)?.let { labelerInputErrors ->
      contextVectorsErrors.assignSum(labelerInputErrors.contextErrors)
      this.propagateRootErrors(labelerInputErrors.rootErrors)
    }

    this.lssEncoder.propagateErrors(
      errors = LSSEncoder.OutputErrors(
        size = latentHeadsErrors.size,
        contextVectors = contextVectorsErrors,
        latentHeads = latentHeadsErrors),
      optimizer = this.lssEncoderOptimizer)
  }

  /**
   * Propagate the [errors] through the virtual root embedding.
   *
   * @param errors the errors
   */
  private fun propagateRootErrors(errors: DenseNDArray) {
    this.updateMethod.update(array = this.parser.model.lssModel.rootEmbedding.array, errors = errors)
  }
}
