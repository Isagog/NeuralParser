/* Copyright 2017-present The KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.transitionbased.templates.inputcontexts

import com.kotlinnlp.neuralparser.language.ParsingSentence
import com.kotlinnlp.neuralparser.parsers.transitionbased.utils.items.DenseItem
import com.kotlinnlp.simplednn.simplemath.ndarray.dense.DenseNDArray
import com.kotlinnlp.syntaxdecoder.context.InputContext

/**
 * The tokens context with an encoding representation for each token.
 *
 * @property sentence a parsing sentence, with the tokens list that is parallel to [items]
 * @property encodingSize the size of the each encoding
 * @property nullItemVector used to represent the encoding of a null item of the decoding window
 * @property trainingMode whether the parser is being trained
 */
abstract class TokensEncodingContext<SelfType: TokensEncodingContext<SelfType>>(
  val sentence: ParsingSentence,
  val encodingSize: Int,
  val nullItemVector: DenseNDArray,
  val trainingMode: Boolean = false
) : InputContext<SelfType, DenseItem> {

  /**
   * A list of dense items, parallel to the sentence tokens and with the same ids.
   */
  override val items: List<DenseItem> = this.sentence.tokens.map { DenseItem(it.id) }

  /**
   * The length of the sentence.
   */
  override val length: Int = this.sentence.tokens.size

  /**
   * The errors associated to the [nullItemVector].
   */
  var nullItemErrors: DenseNDArray? = null
    private set

  /**
   * Check conditions
   */
  init {
    require(nullItemVector.length == this.encodingSize) {
      "nullItemVector size not compatible with declared encodingSize."
    }
  }

  /**
   * Accumulate the given [errors] into the related items at the given [itemIndex].
   *
   * @param itemIndex the index of the item affected by the errors
   * @param errors the errors array to accumulate
   */
  fun accumulateItemErrors(itemIndex: Int?, errors: DenseNDArray) =
    if (itemIndex != null) {
      this.items[itemIndex].accumulateErrors(errors)
    } else {
      this.accumulateNullItemErrors(errors)
    }

  /**
   * @param itemId the id of an item
   *
   * @return get the encoding vector of the item with the given id
   */
  abstract fun getTokenEncoding(itemId: Int?): DenseNDArray

  /**
   * @param errors the errors of the [nullItemVector] to accumulate
   */
  private fun accumulateNullItemErrors(errors: DenseNDArray) {

    if (this.nullItemErrors != null) {
      this.nullItemErrors!!.assignSum(errors)
    } else {
      this.nullItemErrors = errors.copy()
    }
  }
}
