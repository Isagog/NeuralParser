/* Copyright 2017-present The KotlinNLP Authors. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * ------------------------------------------------------------------*/

package com.kotlinnlp.neuralparser.parsers.lhrparser.helpers

import com.kotlinnlp.constraints.Constraint
import com.kotlinnlp.dependencytree.CycleDetectedError
import com.kotlinnlp.dependencytree.DependencyTree
import com.kotlinnlp.linguisticdescription.sentence.token.MorphoSyntacticToken
import com.kotlinnlp.neuralparser.parsers.lhrparser.LatentSyntacticStructure
import com.kotlinnlp.neuralparser.parsers.lhrparser.deprelselectors.MorphoDeprelSelector
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.labeler.DeprelLabeler
import com.kotlinnlp.neuralparser.parsers.lhrparser.neuralmodules.labeler.utils.ScoredDeprel

/**
 * A helper that builds the sentence dependency tree with the best configuration exploring the possible arcs and
 * the related labels with a greedy approach through a beam of parallel states.
 *
 * @param lss the latent syntactic structure of the input sentence
 * @param deprelLabeler a deprel labeler (can be null)
 * @param constraints a list of linguistic constraints (can be null)
 * @param morphoDeprelSelector a morpho-deprel selector used to build a [MorphoSyntacticToken]
 * @param deprelScoreThreshold the score threshold above which to consider a deprel valid
 * @param maxBeamSize the max number of parallel states that the beam supports
 * @param maxForkSize the max number of forks that can be generated from a state
 * @param maxIterations the max number of iterations of solving steps (it is the depth of beam recursion)
 */
internal class DependencyTreeBuilder(
  private val lss: LatentSyntacticStructure,
  private val scoresMap: ArcScores,
  private val deprelLabeler: DeprelLabeler?,
  private val constraints: List<Constraint>?,
  private val morphoDeprelSelector: MorphoDeprelSelector,
  private val deprelScoreThreshold: Double,
  maxBeamSize: Int = 10,
  maxForkSize: Int = 5,
  maxIterations: Int = 10
) : BeamManager<DependencyTreeBuilder.ArcValue, DependencyTreeBuilder.TreeState>(
  valuesMap = lss.sentence.tokens.associate {
    it.id to scoresMap.getSortedHeads(it.id).map { arc ->
      ArcValue(dependentId = it.id, governorId = arc.governorId, score = arc.score)
    }
  },
  maxBeamSize = maxBeamSize,
  maxForkSize = maxForkSize,
  maxIterations = maxIterations
) {

  /**
   * An arc of a state, including information about its dependent token and its index in the related scored arcs list.
   *
   * @property dependentId the id of the dependent of this arc
   * @property governorId the id of the governor of this arc
   * @property score the arc score
   */
  internal data class ArcValue(val dependentId: Int, val governorId: Int, override var score: Double) : Value()

  /**
   * The state that contains the configuration of a possible [DependencyTree].
   *
   * @param elements the list of elements in this state, sorted by diff score
   */
  internal inner class TreeState(elements: List<StateElement<ArcValue>>) : State(elements) {

    /**
     * The global score of the state.
     */
    override val score: Double get() = this.tree.score

    /**
     * The dependency tree of this state.
     */
    val tree = DependencyTree(lss.sentence.tokens.map { it.id })

    /**
     * Whether the [tree] is a fully-connected DAG (Direct Acyclic Graph).
     */
    override var isValid: Boolean = true

    /**
     * Initialize the dependency tree.
     */
    init {

      try {
        elements.forEach {
          if (it.value.governorId > -1)
            this.tree.setArc(dependent = it.value.dependentId, governor = it.value.governorId, score = it.value.score)
          else
            this.tree.setAttachmentScore(dependent = it.value.dependentId, score = it.value.score) // it is the top
        }
      } catch (e: CycleDetectedError) {
        this.isValid = false
      }

      if (!this.tree.hasSingleRoot()) this.isValid = false

      if (this.isValid) deprelLabeler?.let { this.tree.assignLabels() }
    }
  }

  /**
   * Find the best dependency tree that does not violates any linguistic constraint and with the highest global score.
   *
   * @return the best dependency tree built from the given LSS
   */
  fun build(): DependencyTree = this.findBestConfiguration()?.tree ?: this.buildDependencyTree(scoresMap)!!

  /**
   * Build a new state with the given elements.
   *
   * @param elements the elements that compose the building state
   *
   * @return a new state with the given elements
   */
  override fun buildState(elements: List<StateElement<ArcValue>>): TreeState = TreeState(elements)

  /**
   * Build a new dependency tree from the latent syntactic structure [lss], using the given possible attachments.
   *
   * @param scores a map of attachments scores between pairs of tokens
   *
   * @return the annotated dependency tree with the highest score, built from the given LSS, or null if there is no
   *         valid configuration (that does not violate any hard constraint)
   */
  private fun buildDependencyTree(scores: ArcScores): DependencyTree? =
    try {
      DependencyTree(lss.sentence.tokens.map { it.id }).apply {
        assignHeads(scores)
        fixCycles(scores)
        deprelLabeler?.let { assignLabels() }
      }
    } catch (e: DeprelConstraintSolver.InvalidConfiguration) {
      null
    }

  /**
   * Assign the heads to this dependency tree using the highest scoring arcs from the given [scores].
   *
   * @param scores a map of attachments scores between pairs of tokens
   */
  private fun DependencyTree.assignHeads(scores: ArcScores) {

    val (topId: Int, topScore: Double) = scores.findHighestScoringTop()

    this.setAttachmentScore(dependent = topId, score = topScore)

    this.elements.filter { it != topId }.forEach { depId ->

      val (govId: Int, score: Double) = scores.findHighestScoringHead(dependentId = depId, except = listOf(ArcScores.rootId))!!

      this.setArc(
        dependent = depId,
        governor = govId,
        allowCycle = true,
        score = score)
    }
  }

  /**
   * Fix possible cycles using the given [scores].
   *
   * @param scores a map of attachments scores between pairs of tokens
   */
  private fun DependencyTree.fixCycles(scores: ArcScores) = CyclesFixer(this, scores).fixCycles()

  /**
   * Annotate this dependency tree with the labels.
   */
  private fun DependencyTree.assignLabels() {

    val deprelsMap: Map<Int, List<ScoredDeprel>> = this.buildDeprelsMap()

    constraints?.let {
      DeprelConstraintSolver(
        sentence = lss.sentence,
        dependencyTree = this,
        constraints = it,
        morphoDeprelSelector = morphoDeprelSelector,
        scoresMap = deprelsMap
      ).solve()
    }
      ?: deprelsMap.forEach { tokenId, deprels -> this.setDeprel(dependent = tokenId, deprel = deprels.first().value) }
  }

  /**
   * @return a map of valid deprels (sorted by descending score) associated to each token id
   */
  private fun DependencyTree.buildDeprelsMap(): Map<Int, List<ScoredDeprel>> =

    deprelLabeler!!.predict(DeprelLabeler.Input(lss, this)).withIndex().associate { (tokenIndex, prediction) ->

      val tokenId: Int = this.elements[tokenIndex]
      val validDeprels: List<ScoredDeprel> = morphoDeprelSelector.getValidDeprels(
        deprels = prediction,
        sentence = lss.sentence,
        tokenIndex = tokenIndex,
        headIndex = this.getHead(tokenId)?.let { this.getPosition(it) })

      tokenId to validDeprels
        .filter { it.score >= deprelScoreThreshold }
        .notEmptyOr { validDeprels.subList(0, 1) }
    }

  /**
   * @param callback a callback that returns a list
   *
   * @return this list if it is not empty, otherwise the value returned by the callback
   */
  private fun <T> List<T>.notEmptyOr(callback: () -> List<T>): List<T> = if (this.isNotEmpty()) this else callback()
}