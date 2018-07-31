/* Copyright 2018-present LHRParser Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * -----------------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.lhrparser.deprelselectors

import com.kotlinnlp.dependencytree.Deprel
import com.kotlinnlp.linguisticdescription.morphology.Morphology
import com.kotlinnlp.neuralparser.language.ParsingSentence
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodels.labeler.utils.ScoredDeprel

/**
 * The deprel selector to use with the "composite" deprel format.
 */
class CompositeDeprelSelector : MorphoDeprelSelector {

  companion object {

    /**
     * The unknown deprel, returned when no deprel can be selected.
     */
    val UNKNOWN_DEPREL = Deprel(label = "UNKNOWN", direction = Deprel.Position.NULL)
  }

  /**
   * Get the best deprel of a prediction.
   *
   * @param deprels the list of scored deprels
   * @param sentence the input sentence
   * @param tokenIndex the index of the token to which the deprel must be assigned
   *
   * @return the best deprel
   */
  override fun getBestDeprel(deprels: List<ScoredDeprel>, sentence: ParsingSentence, tokenIndex: Int): Deprel =
    deprels.firstOrNull {

      val posTags: List<String> = it.value.extractPosTags()

      sentence.tokens[tokenIndex].morphologies.any { it.list.map { it.type.baseAnnotation } == posTags }

    }?.value ?: UNKNOWN_DEPREL

  /**
   * Get the morphologies that are compatible with the deprel of a token.
   *
   * @param morphologies the list of possible morphologies of the token
   * @param deprel the scored deprel of the token
   *
   * @return the morphologies compatible with the given deprel
   */
  override fun getValidMorphologies(morphologies: List<Morphology>, deprel: ScoredDeprel): List<Morphology> {

    val posTags: List<String> = deprel.value.extractPosTags()

    return morphologies.filter { it.list.map { it.type.baseAnnotation } == posTags }
  }

  /**
   * @return the list of POS tags annotated in the label of this deprel
   */
  private fun Deprel.extractPosTags(): List<String> = this.label.split(" ").map { it.split("~").first() }
}