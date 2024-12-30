package com.sageserpent.kineticmerge.core

import cats.Eq
import com.sageserpent.kineticmerge.core.LongestCommonSubsequence.{CommonSubsequenceSize, Contribution}
import com.typesafe.scalalogging.StrictLogging
import monocle.syntax.all.*

case class LongestCommonSubsequence[Element] private (
    base: IndexedSeq[Contribution[Element]],
    left: IndexedSeq[Contribution[Element]],
    right: IndexedSeq[Contribution[Element]],
    commonSubsequenceSize: CommonSubsequenceSize,
    commonToLeftAndRightOnlySize: CommonSubsequenceSize,
    commonToBaseAndLeftOnlySize: CommonSubsequenceSize,
    commonToBaseAndRightOnlySize: CommonSubsequenceSize
):
  def addBaseDifference(
      baseElement: Element
  ): LongestCommonSubsequence[Element] =
    this
      .focus(_.base)
      .modify(_ :+ Contribution.Difference(baseElement))
  def addLeftDifference(
      leftElement: Element
  ): LongestCommonSubsequence[Element] =
    this
      .focus(_.left)
      .modify(_ :+ Contribution.Difference(leftElement))
  def addRightDifference(
      rightElement: Element
  ): LongestCommonSubsequence[Element] =
    this
      .focus(_.right)
      .modify(_ :+ Contribution.Difference(rightElement))

  def addCommonBaseAndLeft(
      baseElement: Element,
      leftElement: Element
  )(elementSize: Element => Int): LongestCommonSubsequence[Element] =
    this
      .focus(_.base)
      .modify(
        _ :+ Contribution.CommonToBaseAndLeftOnly(baseElement)
      )
      .focus(_.left)
      .modify(
        _ :+ Contribution.CommonToBaseAndLeftOnly(leftElement)
      )
      .focus(_.commonToBaseAndLeftOnlySize)
      .modify(
        _.addCostOfASingleContribution(
          elementSize(baseElement) max elementSize(leftElement)
        )
      )

  def addCommonBaseAndRight(
      baseElement: Element,
      rightElement: Element
  )(elementSize: Element => Int): LongestCommonSubsequence[Element] =
    this
      .focus(_.base)
      .modify(
        _ :+ Contribution.CommonToBaseAndRightOnly(baseElement)
      )
      .focus(_.right)
      .modify(
        _ :+ Contribution.CommonToBaseAndRightOnly(rightElement)
      )
      .focus(_.commonToBaseAndRightOnlySize)
      .modify(
        _.addCostOfASingleContribution(
          elementSize(baseElement) max elementSize(rightElement)
        )
      )

  def addCommonLeftAndRight(
      leftElement: Element,
      rightElement: Element
  )(elementSize: Element => Int): LongestCommonSubsequence[Element] =
    this
      .focus(_.left)
      .modify(
        _ :+ Contribution.CommonToLeftAndRightOnly(leftElement)
      )
      .focus(_.right)
      .modify(
        _ :+ Contribution.CommonToLeftAndRightOnly(rightElement)
      )
      .focus(_.commonToLeftAndRightOnlySize)
      .modify(
        _.addCostOfASingleContribution(
          elementSize(leftElement) max elementSize(rightElement)
        )
      )

  def addCommon(
      baseElement: Element,
      leftElement: Element,
      rightElement: Element
  )(elementSize: Element => Int): LongestCommonSubsequence[Element] =
    this
      .focus(_.base)
      .modify(_ :+ Contribution.Common(baseElement))
      .focus(_.left)
      .modify(_ :+ Contribution.Common(leftElement))
      .focus(_.right)
      .modify(_ :+ Contribution.Common(rightElement))
      .focus(_.commonSubsequenceSize)
      .modify(
        _.addCostOfASingleContribution(
          elementSize(baseElement) max elementSize(leftElement) max elementSize(
            rightElement
          )
        )
      )

  def size: (CommonSubsequenceSize, CommonSubsequenceSize) =
    commonSubsequenceSize -> (commonToLeftAndRightOnlySize plus commonToBaseAndLeftOnlySize plus commonToBaseAndRightOnlySize)
end LongestCommonSubsequence

object LongestCommonSubsequence extends StrictLogging:

  def defaultElementSize[Element](irrelevant: Element): Int = 1

  def of[Element: Eq: Sized](
      base: IndexedSeq[Element],
      left: IndexedSeq[Element],
      right: IndexedSeq[Element]
  ): LongestCommonSubsequence[Element] =
    given orderBySize: Ordering[LongestCommonSubsequence[Element]] =
      given Ordering[CommonSubsequenceSize] =
        Ordering.by(size => size.elementSizeSum)

      Ordering.by(_.size)
    end orderBySize

    val equality = summon[Eq[Element]]
    val sized    = summon[Sized[Element]]

    // TODO: maybe this should be unpacked into three parameters where it is
    // used?
    type PartialResultKey = (Int, Int, Int)

    /** [[PartialResultKey]] keys and their associated
      * [[LongestCommonSubsequence]] solutions are organised into swathes, where
      * each swathe is populated by keys that all share at least one index
      * taking the value of the swathe's labelling index; all other indices in a
      * swathe's keys are lower than the swathe index.<p> For example, the
      * swathe of index 0 contains an entry using indices (0, 0, 0).<p> The
      * swathe of index 1 contains entries using indices (1, 1, 1), (1, 1, 0),
      * (1, 0, 1), (1, 0, 0).<p>This breakdown of keys means that a dynamic
      * programming approach can work up through the swathes, calculating
      * sub-problem solutions that depend only on solutions from within the
      * leading swathe and its predecessor.
      */
    trait Swathes:
      def consultRelevantSwatheForSolution(
          partialResultKey: PartialResultKey
      ): LongestCommonSubsequence[Element]

      def storeSolutionInLeadingSwathe(
          partialResultKey: PartialResultKey,
          longestCommonSubsequence: LongestCommonSubsequence[Element]
      ): Unit
    end Swathes

    object Swathes:
      private val maximumSwatheIndex =
        base.size max left.size max right.size

      def evaluateSolutionsInDependencyOrder(
          action: (Swathes, PartialResultKey) => Unit
      ): LongestCommonSubsequence[Element] =
        object swathes extends Swathes:
          private val upperBoundOfSwatheSizes =
            // Just use a single upper bound for all swathes, which means it is
            // an overestimate for all but the final swathe.
            1 + (1 + base.size) * (1 + left.size) + (1 + base.size) * (1 + right.size) + (1 + left.size) * (1 + right.size)

          private val offsetInStorageEntriesWithAllEqualToSwatheIndex = 0

          private val offsetInStorageEntriesWithLeftEqualToSwatheIndex =
            offsetInStorageEntriesWithAllEqualToSwatheIndex + 1

          private val offsetInStorageEntriesWithRightEqualToSwatheIndex =
            offsetInStorageEntriesWithLeftEqualToSwatheIndex + (1 + base.size) * (1 + right.size)

          private val offsetInStorageEntriesWithBaseEqualToSwatheIndex =
            offsetInStorageEntriesWithRightEqualToSwatheIndex + (1 + base.size) * (1 + left.size)

          private val twoLotsOfStorage = Array(newStorage, newStorage)

          private val notYetAdvanced = -1

          private var _indexOfLeadingSwathe: Int = notYetAdvanced

          def advanceToNextLeadingSwathe(): Boolean =
            val resultSnapshotPriorToMutation = notYetReachedFinalSwathe

            if resultSnapshotPriorToMutation then _indexOfLeadingSwathe += 1

            resultSnapshotPriorToMutation
          end advanceToNextLeadingSwathe

          def topLevelSolution: LongestCommonSubsequence[Element] =
            require(!notYetReachedFinalSwathe)

            twoLotsOfStorage(storageLotForLeadingSwathe)(
              indexFor(
                _indexOfLeadingSwathe,
                (base.size, left.size, right.size)
              )
            )
          end topLevelSolution

          private def notYetReachedFinalSwathe =
            maximumSwatheIndex > _indexOfLeadingSwathe

          def consultRelevantSwatheForSolution(
              partialResultKey: PartialResultKey
          ): LongestCommonSubsequence[Element] =
            require(_indexOfLeadingSwathe != notYetAdvanced)

            logger.debug(s"Consulting solution for $partialResultKey.")

            partialResultKey match
              case (onePastBaseIndex, _, _)
                  if indexOfLeadingSwathe == onePastBaseIndex =>
                twoLotsOfStorage(storageLotForLeadingSwathe)(
                  indexFor(_indexOfLeadingSwathe, partialResultKey)
                )
              case (_, onePastLeftIndex, _)
                  if indexOfLeadingSwathe == onePastLeftIndex =>
                twoLotsOfStorage(storageLotForLeadingSwathe)(
                  indexFor(_indexOfLeadingSwathe, partialResultKey)
                )
              case (_, _, onePastRightIndex)
                  if indexOfLeadingSwathe == onePastRightIndex =>
                twoLotsOfStorage(storageLotForLeadingSwathe)(
                  indexFor(_indexOfLeadingSwathe, partialResultKey)
                )
              case _ =>
                twoLotsOfStorage(storageLotForPrecedingSwathe)(
                  indexFor(_indexOfLeadingSwathe - 1, partialResultKey)
                )
            end match
          end consultRelevantSwatheForSolution

          inline private def storageLotForPrecedingSwathe =
            logger.debug(s"Selecting preceding swathe.")
            (1 + _indexOfLeadingSwathe) % 2
          end storageLotForPrecedingSwathe

          def indexOfLeadingSwathe: Int = _indexOfLeadingSwathe

          inline private def storageLotForLeadingSwathe =
            logger.debug(s"Selecting leading swathe.")
            _indexOfLeadingSwathe % 2
          end storageLotForLeadingSwathe

          inline private def indexFor(
              swatheIndex: Int,
              partialResultKey: PartialResultKey
          ) =
            val result = partialResultKey match
              case (onePastBaseIndex, onePastLeftIndex, onePastRightIndex) =>
                val swatheIndexOnBase =
                  swatheIndex == onePastBaseIndex
                val swatheIndexOnLeft =
                  swatheIndex == onePastLeftIndex
                val swatheIndexOnRight =
                  swatheIndex == onePastRightIndex
                if swatheIndexOnBase && swatheIndexOnLeft && swatheIndexOnRight
                then offsetInStorageEntriesWithAllEqualToSwatheIndex
                else if swatheIndexOnLeft then
                  offsetInStorageEntriesWithLeftEqualToSwatheIndex + onePastBaseIndex * (1 + right.size) + onePastRightIndex
                else if swatheIndexOnRight then
                  offsetInStorageEntriesWithRightEqualToSwatheIndex + onePastBaseIndex * (1 + left.size) + onePastLeftIndex
                else
                  offsetInStorageEntriesWithBaseEqualToSwatheIndex + onePastLeftIndex * (1 + right.size) + onePastRightIndex
                end if

            logger.debug(s"Index for solution: $result.")

            result
          end indexFor

          def storeSolutionInLeadingSwathe(
              partialResultKey: PartialResultKey,
              longestCommonSubsequence: LongestCommonSubsequence[Element]
          ): Unit =
            require(_indexOfLeadingSwathe != notYetAdvanced)

            logger.debug(s"Storing solution for $partialResultKey.")

            twoLotsOfStorage(storageLotForLeadingSwathe)(
              indexFor(_indexOfLeadingSwathe, partialResultKey)
            ) = longestCommonSubsequence
          end storeSolutionInLeadingSwathe

          inline private def newStorage =
            Array.ofDim[LongestCommonSubsequence[Element]](
              upperBoundOfSwatheSizes
            )
        end swathes

        while swathes.advanceToNextLeadingSwathe() do
          logger.debug(
            s"Advanced to swathe of index: ${swathes.indexOfLeadingSwathe}\n"
          )

          val maximumLesserBaseIndex =
            base.size min (swathes.indexOfLeadingSwathe - 1)
          val maximumLesserLeftIndex =
            left.size min (swathes.indexOfLeadingSwathe - 1)
          val maximumLesserRightIndex =
            right.size min (swathes.indexOfLeadingSwathe - 1)

          if base.size >= swathes.indexOfLeadingSwathe then
            logger.debug(
              s"Holding base index at: ${swathes.indexOfLeadingSwathe}."
            )

            // Hold the base index at the maximum for this swathe and evaluate
            // all solutions with lesser left and right indices in dependency
            // order within this swathe...
            if maximumLesserLeftIndex < maximumLesserRightIndex then
              val maximumShortIndex = maximumLesserLeftIndex
              val maximumLongIndex  = maximumLesserRightIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling   <- 0 until maximumShortIndex
                leftIndex <- 0 to ceiling
                rightIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling   <- maximumShortIndex to maximumLongIndex
                leftIndex <- 0 to maximumShortIndex
                rightIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                leftIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                rightIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
            else
              val maximumShortIndex = maximumLesserRightIndex
              val maximumLongIndex  = maximumLesserLeftIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling    <- 0 until maximumShortIndex
                rightIndex <- 0 to ceiling
                leftIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling    <- maximumShortIndex to maximumLongIndex
                rightIndex <- 0 to maximumShortIndex
                leftIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                rightIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                leftIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    swathes.indexOfLeadingSwathe,
                    leftIndex,
                    rightIndex
                  )
                )
              end for
            end if
          end if

          if left.size >= swathes.indexOfLeadingSwathe then
            logger.debug(
              s"Holding left index at: ${swathes.indexOfLeadingSwathe}."
            )

            // Hold the left index at the maximum for this swathe and evaluate
            // all solutions with lesser base and right indices in dependency
            // order within this swathe...
            if maximumLesserBaseIndex < maximumLesserRightIndex then
              val maximumShortIndex = maximumLesserBaseIndex
              val maximumLongIndex  = maximumLesserRightIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling   <- 0 until maximumShortIndex
                baseIndex <- 0 to ceiling
                rightIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling   <- maximumShortIndex to maximumLongIndex
                baseIndex <- 0 to maximumShortIndex
                rightIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                baseIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                rightIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
            else
              val maximumShortIndex = maximumLesserRightIndex
              val maximumLongIndex  = maximumLesserBaseIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling    <- 0 until maximumShortIndex
                rightIndex <- 0 to ceiling
                baseIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling    <- maximumShortIndex to maximumLongIndex
                rightIndex <- 0 to maximumShortIndex
                baseIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                rightIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                baseIndex = ceiling - rightIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    swathes.indexOfLeadingSwathe,
                    rightIndex
                  )
                )
              end for
            end if
          end if

          if right.size >= swathes.indexOfLeadingSwathe then
            logger.debug(
              s"Holding right index at: ${swathes.indexOfLeadingSwathe}."
            )

            // Hold the right index at the maximum for this swathe and evaluate
            // all solutions with lesser base and left indices in dependency
            // order within this swathe...
            if maximumLesserBaseIndex < maximumLesserLeftIndex then
              val maximumShortIndex = maximumLesserBaseIndex
              val maximumLongIndex  = maximumLesserLeftIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling   <- 0 until maximumShortIndex
                baseIndex <- 0 to ceiling
                leftIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling   <- maximumShortIndex to maximumLongIndex
                baseIndex <- 0 to maximumShortIndex
                leftIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                baseIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                leftIndex = ceiling - baseIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
            else
              val maximumShortIndex = maximumLesserLeftIndex
              val maximumLongIndex  = maximumLesserBaseIndex

              // Evaluate along initial short diagonals increasing in length...
              for
                ceiling   <- 0 until maximumShortIndex
                leftIndex <- 0 to ceiling
                baseIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
              // Evaluate along full-length diagonals...
              for
                ceiling   <- maximumShortIndex to maximumLongIndex
                leftIndex <- 0 to maximumShortIndex
                baseIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
              // Evaluate along final short diagonals decreasing in length...
              for
                ceiling <-
                  (1 + maximumLongIndex) to (maximumShortIndex + maximumLongIndex)
                leftIndex <- (ceiling - maximumLongIndex) to maximumShortIndex
                baseIndex = ceiling - leftIndex
              do
                action(
                  swathes,
                  (
                    baseIndex,
                    leftIndex,
                    swathes.indexOfLeadingSwathe
                  )
                )
              end for
            end if
          end if

          if base.size >= swathes.indexOfLeadingSwathe && left.size >= swathes.indexOfLeadingSwathe
          then
            logger.debug(
              s"Holding base and left indices at: ${swathes.indexOfLeadingSwathe}"
            )

            for rightIndex <- 0 to maximumLesserRightIndex do
              action(
                swathes,
                (
                  swathes.indexOfLeadingSwathe,
                  swathes.indexOfLeadingSwathe,
                  rightIndex
                )
              )
            end for
          end if

          if base.size >= swathes.indexOfLeadingSwathe && right.size >= swathes.indexOfLeadingSwathe
          then
            logger.debug(
              s"Holding base and right indices at: ${swathes.indexOfLeadingSwathe}"
            )

            for leftIndex <- 0 to maximumLesserLeftIndex do
              action(
                swathes,
                (
                  swathes.indexOfLeadingSwathe,
                  leftIndex,
                  swathes.indexOfLeadingSwathe
                )
              )
            end for
          end if

          if left.size >= swathes.indexOfLeadingSwathe && right.size >= swathes.indexOfLeadingSwathe
          then
            logger.debug(
              s"Holding left and right indices at: ${swathes.indexOfLeadingSwathe}"
            )

            for baseIndex <- 0 to maximumLesserBaseIndex do
              action(
                swathes,
                (
                  baseIndex,
                  swathes.indexOfLeadingSwathe,
                  swathes.indexOfLeadingSwathe
                )
              )
            end for
          end if

          logger.debug(
            s"Top-level solution for: ${(swathes.indexOfLeadingSwathe, swathes.indexOfLeadingSwathe, swathes.indexOfLeadingSwathe)}."
          )

          if base.size >= swathes.indexOfLeadingSwathe && left.size >= swathes.indexOfLeadingSwathe && right.size >= swathes.indexOfLeadingSwathe
          then
            // Top-level solution for the leading swathe...
            action(
              swathes,
              (
                swathes.indexOfLeadingSwathe,
                swathes.indexOfLeadingSwathe,
                swathes.indexOfLeadingSwathe
              )
            )
          end if
        end while

        swathes.topLevelSolution
      end evaluateSolutionsInDependencyOrder
    end Swathes

    def ofConsultingCacheForSubProblems(
        swathes: Swathes,
        onePastBaseIndex: Int,
        onePastLeftIndex: Int,
        onePastRightIndex: Int
    ): LongestCommonSubsequence[Element] =
      logger.debug(
        s"Calculating solution for ${(onePastBaseIndex, onePastLeftIndex, onePastRightIndex)}."
      )

      assume(0 <= onePastBaseIndex)
      assume(0 <= onePastLeftIndex)
      assume(0 <= onePastRightIndex)

      val baseIsExhausted  = 0 == onePastBaseIndex
      val leftIsExhausted  = 0 == onePastLeftIndex
      val rightIsExhausted = 0 == onePastRightIndex

      (baseIsExhausted, leftIsExhausted, rightIsExhausted) match
        case (true, true, true) | (true, true, false) | (true, false, true) |
            (false, true, true) =>
          // There is nothing left to compare from one side to any other...
          LongestCommonSubsequence(
            base = Vector.tabulate(onePastBaseIndex)(index =>
              Contribution.Difference(base(index))
            ),
            left = Vector.tabulate(onePastLeftIndex)(index =>
              Contribution.Difference(left(index))
            ),
            right = Vector.tabulate(onePastRightIndex)(index =>
              Contribution.Difference(right(index))
            ),
            commonSubsequenceSize = CommonSubsequenceSize.zero,
            commonToLeftAndRightOnlySize = CommonSubsequenceSize.zero,
            commonToBaseAndLeftOnlySize = CommonSubsequenceSize.zero,
            commonToBaseAndRightOnlySize = CommonSubsequenceSize.zero
          )

        case (true, false, false) =>
          // Base is exhausted...
          val leftIndex  = onePastLeftIndex - 1
          val rightIndex = onePastRightIndex - 1

          val leftElement  = left(leftIndex)
          val rightElement = right(rightIndex)

          val leftEqualsRight = equality.eqv(leftElement, rightElement)

          if leftEqualsRight then
            swathes
              .consultRelevantSwatheForSolution((0, leftIndex, rightIndex))
              .addCommonLeftAndRight(leftElement, rightElement)(
                sized.sizeOf
              )
          else
            val resultDroppingTheEndOfTheLeft =
              swathes
                .consultRelevantSwatheForSolution(
                  (0, leftIndex, onePastRightIndex)
                )
                .addLeftDifference(leftElement)

            val resultDroppingTheEndOfTheRight =
              swathes
                .consultRelevantSwatheForSolution(
                  (0, onePastLeftIndex, rightIndex)
                )
                .addRightDifference(rightElement)

            orderBySize.max(
              resultDroppingTheEndOfTheLeft,
              resultDroppingTheEndOfTheRight
            )
          end if

        case (false, true, false) =>
          // Left is exhausted...
          val baseIndex  = onePastBaseIndex - 1
          val rightIndex = onePastRightIndex - 1

          val baseElement  = base(baseIndex)
          val rightElement = right(rightIndex)

          val baseEqualsRight = equality.eqv(baseElement, rightElement)

          if baseEqualsRight then
            swathes
              .consultRelevantSwatheForSolution((baseIndex, 0, rightIndex))
              .addCommonBaseAndRight(baseElement, rightElement)(
                sized.sizeOf
              )
          else
            val resultDroppingTheEndOfTheBase =
              swathes
                .consultRelevantSwatheForSolution(
                  (baseIndex, 0, onePastRightIndex)
                )
                .addBaseDifference(baseElement)

            val resultDroppingTheEndOfTheRight =
              swathes
                .consultRelevantSwatheForSolution(
                  (onePastBaseIndex, 0, rightIndex)
                )
                .addRightDifference(rightElement)

            orderBySize.max(
              resultDroppingTheEndOfTheBase,
              resultDroppingTheEndOfTheRight
            )
          end if

        case (false, false, true) =>
          // Right is exhausted...
          val baseIndex = onePastBaseIndex - 1
          val leftIndex = onePastLeftIndex - 1

          val baseElement = base(baseIndex)
          val leftElement = left(leftIndex)

          val baseEqualsLeft = equality.eqv(baseElement, leftElement)

          if baseEqualsLeft then
            swathes
              .consultRelevantSwatheForSolution((baseIndex, leftIndex, 0))
              .addCommonBaseAndLeft(baseElement, leftElement)(
                sized.sizeOf
              )
          else
            val resultDroppingTheEndOfTheBase =
              swathes
                .consultRelevantSwatheForSolution(
                  (baseIndex, onePastLeftIndex, 0)
                )
                .addBaseDifference(baseElement)

            val resultDroppingTheEndOfTheLeft =
              swathes
                .consultRelevantSwatheForSolution(
                  (onePastBaseIndex, leftIndex, 0)
                )
                .addLeftDifference(leftElement)

            orderBySize.max(
              resultDroppingTheEndOfTheBase,
              resultDroppingTheEndOfTheLeft
            )
          end if

        case (false, false, false) =>
          // Nothing is exhausted...
          val baseIndex  = onePastBaseIndex - 1
          val leftIndex  = onePastLeftIndex - 1
          val rightIndex = onePastRightIndex - 1

          val baseElement  = base(baseIndex)
          val leftElement  = left(leftIndex)
          val rightElement = right(rightIndex)

          val baseEqualsLeft  = equality.eqv(baseElement, leftElement)
          val baseEqualsRight = equality.eqv(baseElement, rightElement)

          if baseEqualsLeft && baseEqualsRight
          then
            swathes
              .consultRelevantSwatheForSolution(
                (baseIndex, leftIndex, rightIndex)
              )
              .addCommon(baseElement, leftElement, rightElement)(
                sized.sizeOf
              )
          else
            val leftEqualsRight = equality.eqv(leftElement, rightElement)

            // NOTE: at this point, we can't have any two of
            // `baseEqualsLeft`, `baseEqualsRight` or `leftEqualsRight`
            // being true - because by transitive equality, that would imply
            // all three sides are equal, and thus we should be following
            // other branch. So we have to use all the next three bindings
            // one way or the other...

            val resultDroppingTheEndOfTheBase =
              swathes
                .consultRelevantSwatheForSolution(
                  (baseIndex, onePastLeftIndex, onePastRightIndex)
                )
                .addBaseDifference(baseElement)

            val resultDroppingTheEndOfTheLeft =
              swathes
                .consultRelevantSwatheForSolution(
                  (onePastBaseIndex, leftIndex, onePastRightIndex)
                )
                .addLeftDifference(leftElement)

            val resultDroppingTheEndOfTheRight =
              swathes
                .consultRelevantSwatheForSolution(
                  (onePastBaseIndex, onePastLeftIndex, rightIndex)
                )
                .addRightDifference(rightElement)

            val resultDroppingTheBaseAndLeft =
              if baseEqualsLeft then
                swathes
                  .consultRelevantSwatheForSolution(
                    (baseIndex, leftIndex, onePastRightIndex)
                  )
                  .addCommonBaseAndLeft(baseElement, leftElement)(
                    sized.sizeOf
                  )
              else
                orderBySize.max(
                  resultDroppingTheEndOfTheBase,
                  resultDroppingTheEndOfTheLeft
                )
              end if
            end resultDroppingTheBaseAndLeft

            val resultDroppingTheBaseAndRight =
              if baseEqualsRight then
                swathes
                  .consultRelevantSwatheForSolution(
                    (baseIndex, onePastLeftIndex, rightIndex)
                  )
                  .addCommonBaseAndRight(baseElement, rightElement)(
                    sized.sizeOf
                  )
              else
                orderBySize.max(
                  resultDroppingTheEndOfTheBase,
                  resultDroppingTheEndOfTheRight
                )
              end if
            end resultDroppingTheBaseAndRight

            val resultDroppingTheLeftAndRight =
              if leftEqualsRight then
                swathes
                  .consultRelevantSwatheForSolution(
                    (onePastBaseIndex, leftIndex, rightIndex)
                  )
                  .addCommonLeftAndRight(leftElement, rightElement)(
                    sized.sizeOf
                  )
              else
                orderBySize.max(
                  resultDroppingTheEndOfTheLeft,
                  resultDroppingTheEndOfTheRight
                )
              end if
            end resultDroppingTheLeftAndRight

            Iterator(
              resultDroppingTheBaseAndLeft,
              resultDroppingTheBaseAndRight,
              resultDroppingTheLeftAndRight
            ).max(orderBySize)
          end if
      end match
    end ofConsultingCacheForSubProblems

    // Brute-forced and ignorant dynamic programming. Completely unsafe reliance
    // on imperative updates priming each sub-problem solution in the leading
    // swathe prior to its use. Got to love it!
    Swathes.evaluateSolutionsInDependencyOrder {
      case (
            swathes,
            partialResultKey @ (
              onePastBaseIndex,
              onePastLeftIndex,
              onePastRightIndex
            )
          ) =>
        swathes.storeSolutionInLeadingSwathe(
          partialResultKey,
          ofConsultingCacheForSubProblems(
            swathes,
            onePastBaseIndex,
            onePastLeftIndex,
            onePastRightIndex
          )
        )
    }
  end of

  trait Sized[Element]:
    def sizeOf(element: Element): Int
  end Sized

  /** @todo
    *   The parameter [[length]] needs review - its only use is by
    *   [[LongestCommonSubsequenceTest.theLongestCommonSubsequenceUnderpinsAllThreeResults]].
    *   It's great that said test passes, but could it be recast to not use this
    *   parameter?
    * @param length
    * @param elementSizeSum
    */
  case class CommonSubsequenceSize(
      length: Int,
      elementSizeSum: Int
  ):
    def addCostOfASingleContribution(size: Int): CommonSubsequenceSize = this
      .focus(_.length)
      .modify(1 + _)
      .focus(_.elementSizeSum)
      .modify(size + _)

    def plus(that: CommonSubsequenceSize): CommonSubsequenceSize =
      CommonSubsequenceSize(
        length = this.length + that.length,
        elementSizeSum = this.elementSizeSum + that.elementSizeSum
      )
  end CommonSubsequenceSize

  enum Contribution[Element]:
    case Common(
        element: Element
    )
    case Difference(
        element: Element
    )
    case CommonToBaseAndLeftOnly(
        element: Element
    )
    case CommonToBaseAndRightOnly(
        element: Element
    )
    case CommonToLeftAndRightOnly(
        element: Element
    )

    def element: Element
  end Contribution

  object CommonSubsequenceSize:
    val zero = CommonSubsequenceSize(length = 0, elementSizeSum = 0)
  end CommonSubsequenceSize
end LongestCommonSubsequence
