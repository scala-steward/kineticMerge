package com.sageserpent.kineticmerge.core

import cats.Eq
import com.google.common.hash.{Hashing, PrimitiveSink}
import com.sageserpent.kineticmerge.core.ExpectyFlavouredAssert.*
import com.sageserpent.kineticmerge.core.PartitionedThreeWayTransform.Input
import com.sageserpent.kineticmerge.core.Token.tokens
import com.sageserpent.kineticmerge.core.merge.MergedWithConflicts
import org.junit.jupiter.api.Test
import org.rabinfingerprint.polynomial.Polynomial
import org.rabinfingerprint.polynomial.Polynomial.{Reducibility, createFromBytes}
import pprint.*

import scala.annotation.tailrec
import scala.util.Random

class MergeTokensTest extends ProseExamples:
  @Test
  def proseCanBeMerged(): Unit =
    val Right(
      MergedWithConflicts(leftElements, rightElements)
    ) = mergeTokens(
      base = tokens(wordsworth).get,
      left = tokens(jobsworth).get,
      right = tokens(emsworth).get
    ): @unchecked

    pprintln(leftElements.map(_.text).mkString)
    println("*********************************")
    pprintln(rightElements.map(_.text).mkString)
  end proseCanBeMerged
end MergeTokensTest

trait ProseExamples:
  protected val scalaStuff =
    """
      |package com.sageserpent.kineticmerge.core
      |
      |import org.junit.jupiter.api.Assertions.fail
      |
      |import scala.annotation.tailrec
      |
      |extension [Element](sequence: Seq[Element])
      |  /* Replacement for ```should contain inOrderElementsOf```; as I'm not sure if
      |   * that actually detects subsequences correctly in the presence of duplicates. */
      |  def isSubsequenceOf(
      |      anotherSequence: Seq[? >: Element]
      |  ): Unit =
      |    isSubsequenceOf(anotherSequence, negated = false)
      |
      |  def isNotSubsequenceOf(
      |      anotherSequence: Seq[? >: Element]
      |  ): Unit =
      |    isSubsequenceOf(anotherSequence, negated = true)
      |
      |  private def isSubsequenceOf[ElementSupertype >: Element](
      |      anotherSequence: Seq[ElementSupertype],
      |      negated: Boolean
      |  ): Unit =
      |    @tailrec
      |    def verify(
      |        sequenceRemainder: Seq[Element],
      |        anotherSequenceRemainder: Seq[ElementSupertype],
      |        matchingPrefix: Seq[Element]
      |    ): Unit =
      |      if sequenceRemainder.isEmpty then
      |        if negated then
      |          fail(
      |            s"Assertion failed because $sequence is a subsequence of $anotherSequence."
      |          )
      |      else if anotherSequenceRemainder.isEmpty then
      |        if !negated then
      |          if matchingPrefix.isEmpty then
      |            fail(
      |              s"Assertion failed because $sequence is not a subsequence of $anotherSequence - no prefix matches found, either."
      |            )
      |          else
      |            fail(
      |              s"Assertion failed because $sequence is not a subsequence of $anotherSequence, matched prefix $matchingPrefix but failed to find the remaining $sequenceRemainder."
      |            )
      |      else if sequenceRemainder.head == anotherSequenceRemainder.head then
      |        verify(
      |          sequenceRemainder.tail,
      |          anotherSequenceRemainder.tail,
      |          matchingPrefix :+ sequenceRemainder.head
      |        )
      |      else
      |        verify(
      |          sequenceRemainder,
      |          anotherSequenceRemainder.tail,
      |          matchingPrefix
      |        )
      |      end if
      |    end verify
      |
      |    verify(sequence, anotherSequence, sequence.empty)
      |  end isSubsequenceOf
      |end extension
      |""".stripMargin

  protected val wordsworth =
    """
      |I wandered lonely as a cloud
      |That floats on high o'er vales and hills,
      |When all at once I saw a crowd,
      |A host, of golden daffodils;
      |Beside the lake, beneath the trees,
      |Fluttering and dancing in the breeze.
      |
      |Continuous as the stars that shine
      |And twinkle on the milky way,
      |They stretched in never-ending line
      |Along the margin of a bay:
      |Ten thousand saw I at a glance,
      |Tossing their heads in sprightly dance.
      |
      |The waves beside them danced; but they
      |Out-did the sparkling waves in glee:
      |A poet could not but be gay,
      |In such a jocund company:
      |I gazed—and gazed—but little thought
      |What wealth the show to me had brought:
      |
      |For oft, when on my couch I lie
      |In vacant or in pensive mood,
      |They flash upon that inward eye
      |Which is the bliss of solitude;
      |And then my heart with pleasure fills,
      |And dances with the daffodils.
      |""".stripMargin

  protected val jobsworth =
    """
      |I wandered lonely as a cloud
      |That floats on high o'er vales and hills,
      |When all at once I saw a crowd,
      |A host, of golden daffodils;
      |Beside the lake, beneath the trees,
      |Fluttering and dancing in the breeze.
      |
      |I thought, 'Was this part of the job role?'.
      |'Should I be expected to deal with flowers?'
      |The waves beside them danced; but they
      |Out-did the sparkling waves in glee:
      |A poet could not but be gay,
      |In such a jocund company:
      |I gazed—and gazed—but thought only of
      |raising this in the next Zoom meeting.
      |
      |For oft, when on my Aeron I slouch
      |In vacant or in pensive mood,
      |They flash upon that inward eye
      |Which is the bliss of solitude;
      |And then my heart with pleasure fills,
      |And sends an email to human resources.
      |""".stripMargin

  protected val emsworth =
    """
      |I wandered lonely as a cloud
      |That floats on high o'er vales and hills,
      |When all at once I saw a crowd,
      |A host, of small fishing boats;
      |Astride the sea, beneath the quay,
      |Rocking and swaying in the breeze.
      |
      |Why this allusion?
      |I Havant a clue!
      |Along the margin of a bay:
      |Ten thousand (well, maybe not quite) saw I at a glance,
      |Tossing their heads in sprightly dance.
      |
      |The waves beside them danced; but they
      |Out-did the sparkling waves in glee:
      |A poet could not but be gay,
      |In such a jocund company:
      |I gazed—and gazed—but little thought
      |What wealth the show to me had brought:
      |
      |For oft, when on my couch I lie
      |In vacant or in pensive mood,
      |They flash upon that inward eye
      |Which is the bliss of solitude;
      |And then my heart with pleasure fills,
      |And sashays with the fishing boats.
      |""".stripMargin
end ProseExamples
