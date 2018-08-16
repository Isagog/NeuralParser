/* Copyright 2017-present The KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.helpers

import com.kotlinnlp.dependencytree.DependencyTree
import com.kotlinnlp.dependencytree.Deprel
import com.kotlinnlp.dependencytree.POSTag
import com.kotlinnlp.conllio.Sentence as CoNLLSentence
import com.kotlinnlp.conllio.Token as CoNLLToken

/**
 * Build the [DependencyTree] of this sentence.
 *
 * @return a new dependency tree of this sentence
 */
internal fun CoNLLSentence.buildDependencyTree(): DependencyTree {

  val dependencyTree = DependencyTree(size = this.tokens.size)

  this.tokens.forEach { dependencyTree.addArc(it) }

  return dependencyTree
}

/**
 * Add the arc defined by the id, head and deprel of a given [token].
 *
 * @param token a token
 */
private fun DependencyTree.addArc(token: CoNLLToken) {

  val head: Int = token.head!!

  val deprel = Deprel(
    label = token.deprel,
    direction = when {
      head == 0 -> Deprel.Position.ROOT
      head > token.id -> Deprel.Position.LEFT
      else -> Deprel.Position.RIGHT
    })

  val dependentID = token.id - 1

  if (head > 0) {
    this.setArc(dependent = dependentID, governor = head - 1, deprel = deprel, posTag = POSTag(label = token.pos))
  } else {
    this.setDeprel(dependent = dependentID, deprel = deprel)
    this.setPosTag(dependent = dependentID, posTag = POSTag(label = token.pos))
  }
}
