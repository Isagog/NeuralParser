/* Copyright 2017-present The KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.transitionbased.templates.supportstructure.singleprediction

import com.kotlinnlp.neuralparser.parsers.transitionbased.templates.supportstructure.OutputErrorsInit
import com.kotlinnlp.simplednn.core.neuralprocessor.feedforward.FeedforwardNeuralProcessor
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArray
import com.kotlinnlp.syntaxdecoder.modules.supportstructure.DecodingSupportStructure

/**
 * The decoding support structure of an actions scorer that contains a [FeedforwardNeuralProcessor].
 * (SP = Single Prediction).
 */
interface SPSupportStructure : DecodingSupportStructure {

  /**
   * The neural processor used to score actions.
   */
  val processor: FeedforwardNeuralProcessor<DenseNDArray>

  /**
   * The default initialization of the [processor] output errors.
   */
  val outputErrorsInit: OutputErrorsInit
}