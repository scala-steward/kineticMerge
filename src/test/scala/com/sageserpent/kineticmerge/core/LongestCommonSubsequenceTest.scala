package com.sageserpent.kineticmerge.core

import com.eed3si9n.expecty.Expecty
import com.sageserpent.americium.Trials
import com.sageserpent.americium.Trials.api as trialsApi
import com.sageserpent.americium.junit5.*
import com.sageserpent.kineticmerge.core.LongestCommonSubsequence.Contribution
import com.sageserpent.kineticmerge.core.LongestCommonSubsequenceTest.{Element, TestCase, assert}
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{DynamicTest, Test, TestFactory, TestInstance}

import scala.annotation.tailrec

class LongestCommonSubsequenceTest:
  val coreValues: Trials[Element] = trialsApi.choose('a' to 'z')

  val additionalValues: Trials[Element] = trialsApi.choose('A' to 'Z')
  val maximumSize                       = 30
  val testCases: Trials[TestCase] = (for
    core <- sizes(maximumSize)
      .filter(2 < _)
      .flatMap(coreValues.lotsOfSize[Vector[Element]])

    interleaveForBase <- sizes(maximumSize).flatMap(
      additionalValues.lotsOfSize[Vector[Element]]
    )
    base <- trialsApi.pickAlternatelyFrom(
      shrinkToRoundRobin = true,
      core,
      interleaveForBase
    )
    interleaveForLeft <- sizes(maximumSize).flatMap(
      additionalValues.lotsOfSize[Vector[Element]]
    )
    left <- trialsApi.pickAlternatelyFrom(
      shrinkToRoundRobin = true,
      core,
      interleaveForLeft
    )
    interleaveForRight <- sizes(maximumSize).flatMap(
      additionalValues.lotsOfSize[Vector[Element]]
    )
    right <- trialsApi.pickAlternatelyFrom(
      shrinkToRoundRobin = true,
      core,
      interleaveForRight
    )
    if core != base || core != left || core != right
  yield TestCase(core, base, left, right))

  def sizes(maximumSize: Int): Trials[Int] = trialsApi.alternateWithWeights(
    1  -> trialsApi.only(0),
    10 -> trialsApi.integers(1, maximumSize)
  )

  @TestFactory
  def theResultsCorrespondToTheOriginalSequences(): DynamicTests =
    testCases
      .withLimit(100)
      .dynamicTests(
        (
          testCase: TestCase
        ) =>
          val LongestCommonSubsequence(base, left, right, size) =
            LongestCommonSubsequence
              .of(testCase.base, testCase.left, testCase.right)(
                _ == _
              )

          assert(base.map(_.element) == testCase.base)
          assert(left.map(_.element) == testCase.left)
          assert(right.map(_.element) == testCase.right)
      )

  end theResultsCorrespondToTheOriginalSequences

  @TestFactory
  def theLongestCommonSubsequenceUnderpinsAllThreeResults(): DynamicTests =
    testCases
      .withLimit(500)
      .dynamicTests(
        (
          testCase: TestCase
        ) =>
          val coreSize = testCase.core.size

          extension (sequence: IndexedSeq[Contribution[Element]])
            private def verifyLongestCommonSubsequence(
                elements: IndexedSeq[Element]
            ): Unit =
              val indexedCommonParts: IndexedSeq[(Int, Element)] =
                sequence.zipWithIndex.collect:
                  case (common: Contribution.Common[Element], index) =>
                    index -> common.element

              val commonSubsequence = indexedCommonParts.map(_._2)

              val _ = commonSubsequence isSubsequenceOf testCase.base
              val _ = commonSubsequence isSubsequenceOf testCase.left
              val _ = commonSubsequence isSubsequenceOf testCase.right

              assert(commonSubsequence.size >= coreSize)

              val indexedDifferences: IndexedSeq[(Int, Element)] =
                sequence.zipWithIndex.collect:
                  case (difference: Contribution.Difference[Element], index) =>
                    index -> difference.element

              for (differenceIndex, difference) <- indexedDifferences do
                val (leadingCommonIndices, trailingCommonIndices) =
                  indexedCommonParts.span { case (commonIndex, _) =>
                    differenceIndex > commonIndex
                  }

                val viveLaDifférence: IndexedSeq[Element] =
                  leadingCommonIndices.map(
                    _._2
                  ) ++ (difference +: trailingCommonIndices.map(_._2))

                (viveLaDifférence isNotSubsequenceOf testCase.base)
                  .orElse(
                    viveLaDifférence isNotSubsequenceOf testCase.left
                  )
                  .orElse(
                    viveLaDifférence isNotSubsequenceOf testCase.right
                  )
                  .left
                  .foreach(fail _)
              end for

              if commonSubsequence != elements then
                assert(indexedDifferences.nonEmpty)
              end if
          end extension

          val LongestCommonSubsequence(base, left, right, size) =
            LongestCommonSubsequence
              .of(testCase.base, testCase.left, testCase.right)(
                _ == _
              )

          // NOTE: the common subsequence aspect is checked against the
          // corresponding sequence it was derived from, *not* against the core.
          // This is because the interleaves for the base, left and right
          // sequences may add elements that form an alternative longest common
          // subsequence that contradicts the core one, but has at least the
          // same size. All the core sequence does is to guarantee that there
          // will be *some* common subsequence.
          // NASTY HACK: placate IntelliJ with these underscore bindings.
          val _ =
            base verifyLongestCommonSubsequence testCase.base
          val _ =
            left verifyLongestCommonSubsequence testCase.left
          val _ =
            right verifyLongestCommonSubsequence testCase.right

          // NOTE: The reason for the lower bound on size (rather than strict
          // equality) is because the interleaves for the base, left and right
          // sequences may either augment the core sequence by coincidence, or
          // form an alternative one that is longer.
          assert(size >= coreSize)
      )
  end theLongestCommonSubsequenceUnderpinsAllThreeResults

end LongestCommonSubsequenceTest

object LongestCommonSubsequenceTest:
  type Element = Char

  val assert: Expecty = new Expecty:
    override val showLocation: Boolean = true
    override val showTypes: Boolean    = true
  end assert
  case class TestCase(
      core: Vector[Element],
      base: Vector[Element],
      left: Vector[Element],
      right: Vector[Element]
  )
end LongestCommonSubsequenceTest

extension [Element](sequence: Seq[Element])
  /* Replacement for ```should contain inOrderElementsOf```; as I'm not sure if
   * that actually detects subsequences correctly in the presence of duplicates. */
  def isSubsequenceOf(
      anotherSequence: Seq[? >: Element]
  ): Either[String, Unit] =
    isSubsequenceOf(anotherSequence, negated = false)

  def isNotSubsequenceOf(
      anotherSequence: Seq[? >: Element]
  ): Either[String, Unit] =
    isSubsequenceOf(anotherSequence, negated = true)

  private def isSubsequenceOf[ElementSupertype >: Element](
      anotherSequence: Seq[ElementSupertype],
      negated: Boolean
  ): Either[String, Unit] =
    @tailrec
    def verify(
        sequenceRemainder: Seq[Element],
        anotherSequenceRemainder: Seq[ElementSupertype],
        matchingPrefix: Seq[Element]
    ): Either[String, Unit] =
      if sequenceRemainder.isEmpty then
        if negated then Left(s"$sequence is a subsequence of $anotherSequence.")
        else Right(())
      else if anotherSequenceRemainder.isEmpty then
        if negated then Right(())
        else if matchingPrefix.isEmpty then
          Left(
            s"$sequence is not a subsequence of $anotherSequence - no prefix matches found, either."
          )
        else
          Left(
            s"$sequence is not a subsequence of $anotherSequence, matched prefix $matchingPrefix but failed to find the remaining $sequenceRemainder."
          )
      else if sequenceRemainder.head == anotherSequenceRemainder.head then
        verify(
          sequenceRemainder.tail,
          anotherSequenceRemainder.tail,
          matchingPrefix :+ sequenceRemainder.head
        )
      else
        verify(
          sequenceRemainder,
          anotherSequenceRemainder.tail,
          matchingPrefix
        )
      end if
    end verify

    verify(sequence, anotherSequence, sequence.empty)
  end isSubsequenceOf
end extension
