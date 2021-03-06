/* Copyright 2018-present KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * -----------------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules

import com.kotlinnlp.simplednn.core.functionalities.losses.SoftmaxCrossEntropyCalculator
import com.kotlinnlp.simplednn.core.neuralprocessor.NeuralProcessor
import com.kotlinnlp.simplednn.core.optimizer.ParamsErrorsList
import com.kotlinnlp.simplednn.deeplearning.attention.pointernetwork.PointerNetworkModel
import com.kotlinnlp.simplednn.deeplearning.attention.pointernetwork.PointerNetworkProcessor
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArray
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArrayFactory

/**
 * The PositionalEncoder.
 *
 * @param model the model of the pointer network
 * @property id an identification number useful to track a specific encoder
 */
class PositionalEncoder(
  private val model: PointerNetworkModel,
  override val id: Int = 0
) : NeuralProcessor<
  List<DenseNDArray>, // InputType
  List<DenseNDArray>, // OutputType
  List<DenseNDArray>, // ErrorsType
  PointerNetworkProcessor.InputErrors // InputErrorsType
  > {

  companion object {

    /**
     * @param predictions the list of prediction
     *
     * @return the errors of the given predictions
     */
    fun calculateErrors(predictions: List<DenseNDArray>): List<DenseNDArray> {

      return predictions.mapIndexed { index, prediction ->
        val expectedValues = DenseNDArrayFactory.oneHotEncoder(length = predictions.size, oneAt = index)
        SoftmaxCrossEntropyCalculator.calculateErrors(output = prediction, outputGold = expectedValues)
      }
    }
  }

  /**
   * Propagate the errors to the input.
   */
  override val propagateToInput: Boolean = true

  /**
   * The pointer processor used as encoder.
   */
  private val encoder = PointerNetworkProcessor(this.model)

  /**
   * The Forward.
   *
   * @param input the input
   *
   * @return the result of the forward
   */
  override fun forward(input: List<DenseNDArray>): List<DenseNDArray> {

    this.encoder.setInputSequence(input)

    return input.map { this.encoder.forward(it) }
  }

  /**
   * The Backward.
   *
   * @param outputErrors the errors of the last forward
   */
  override fun backward(outputErrors: List<DenseNDArray>) {
    this.encoder.backward(outputErrors)
  }

  /**
   * Return the input errors of the last backward.
   *
   * @param copy whether to return by value or by reference (default true)
   *
   * @return the input errors
   */
  override fun getInputErrors(copy: Boolean): PointerNetworkProcessor.InputErrors = this.encoder.getInputErrors()

  /**
   * Return the params errors of the last backward.
   *
   * @param copy a Boolean indicating whether the returned errors must be a copy or a reference (default true)
   *
   * @return the parameters errors
   */
  override fun getParamsErrors(copy: Boolean): ParamsErrorsList = this.encoder.getParamsErrors(copy = copy)
}
