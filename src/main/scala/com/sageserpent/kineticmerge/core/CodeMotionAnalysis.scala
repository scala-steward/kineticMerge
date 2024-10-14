package com.sageserpent.kineticmerge.core

import cats.implicits.catsKernelOrderingForOrder
import cats.instances.seq.*
import cats.{Eq, Order}
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.google.common.hash.{Funnel, HashFunction}
import com.sageserpent.kineticmerge
import com.sageserpent.kineticmerge.{NoProgressRecording, ProgressRecording, ProgressRecordingSession, core}
import com.typesafe.scalalogging.StrictLogging
import de.sciss.fingertree.RangedSeq
import monocle.syntax.all.*

import java.lang.Byte as JavaByte
import scala.annotation.tailrec
import scala.collection.immutable.{MultiDict, SortedMultiSet}
import scala.collection.parallel.CollectionConverters.*
import scala.collection.{SortedMultiDict, mutable}
import scala.util.Using

trait CodeMotionAnalysis[Path, Element]:
  def base: Map[Path, File[Element]]
  def left: Map[Path, File[Element]]
  def right: Map[Path, File[Element]]

  def matchesFor(
      section: Section[Element]
  ): collection.Set[Match[Section[Element]]]
end CodeMotionAnalysis

object CodeMotionAnalysis extends StrictLogging:
  /** Analyse code motion from the sources of {@code base} to both {@code left}
    * and {@code right} , breaking them into [[File]] and thence [[Section]]
    * instances.
    *
    * Where a section moves from {@code base} , it enters into a match with one
    * or both corresponding sections in {@code left} and {@code right} .
    *
    * @note
    *   Although code motion is strictly speaking relative to the base sources,
    *   if the same section is added into both the left and right sources as a
    *   coincidental insertion (so not present in the base sources), this is
    *   treated as a match across the left and right sources anyway.
    * @param base
    *   The common base sources from which the left and right sources are
    *   derived.
    * @param left
    *   'Our' sources, from the Git standpoint...
    * @param right
    *   'Their' sources, from the Git standpoint...
    * @param configuration
    *   [[Configuration]] parameter object.
    * @tparam Path
    * @return
    *   A [[CodeMotionAnalysis]] that contains a breakdown into [[File]]
    *   instances and thence into [[Section]] instances for each of the three
    *   sources.
    */
  def of[Path, Element: Eq: Order: Funnel](
      base: Sources[Path, Element],
      left: Sources[Path, Element],
      right: Sources[Path, Element]
  )(
      configuration: Configuration
  )(using
      hashFunction: HashFunction
  ): Either[Throwable, CodeMotionAnalysis[Path, Element]] =
    // Yes, this entire method could be moved into `Configuration`, but the
    // level of indentation of quite a lot of code would be increased. Anyway,
    // it's only configuration, after all - performing the analysis belongs to
    // the companion object for `CodeMotionAnalysis`.
    import configuration.*

    val newline = "\n"

    val baseSizesByPath = base.filesByPath.map { case (path, file) =>
      path -> file.size
    }

    logger.debug(
      s"Analysis considering base paths:\n\n${baseSizesByPath.mkString(newline)}\n"
    )

    val leftSizesByPath = left.filesByPath.map { case (path, file) =>
      path -> file.size
    }

    logger.debug(
      s"Analysis considering left paths:\n\n${leftSizesByPath.mkString(newline)}\n"
    )

    val rightSizesByPath = right.filesByPath.map { case (path, file) =>
      path -> file.size
    }

    logger.debug(
      s"Analysis considering right paths:\n\n${rightSizesByPath.mkString(newline)}\n"
    )

    // TODO: suppose all the sources are empty? Could this happen?
    val fileSizes = SortedMultiSet.from(
      baseSizesByPath.values ++ leftSizesByPath.values ++ rightSizesByPath.values
    )

    val totalContentSize = fileSizes.sum

    val minimumFileSizeAcrossAllFilesOverAllSides = fileSizes.head

    def thresholdSizeForMatching(fileSize: Int) =
      (thresholdSizeFractionForMatching * fileSize).floor.toInt

    // This is the minimum window size that would be allowed in *some* file
    // across the sources.
    val minimumWindowSizeAcrossAllFilesOverAllSides =
      minimumMatchSize max thresholdSizeForMatching(
        minimumFileSizeAcrossAllFilesOverAllSides
      )

    // The penultimate largest file size from the three sides is the largest
    // potential match size - because a match has to span at least two sides.
    val maximumPossibleMatchSize =
      Seq(
        baseSizesByPath.values.maxOption,
        leftSizesByPath.values.maxOption,
        rightSizesByPath.values.maxOption
      ).flatten.sorted(Ordering[Int].reverse).take(2).last

    val maximumFileSizeAcrossAllFilesOverAllSides = fileSizes.last

    // This is the minimum window size that would be allowed in *all* files
    // across the sources.
    val minimumSureFireWindowSizeAcrossAllFilesOverAllSides =
      maximumPossibleMatchSize min (minimumMatchSize max thresholdSizeForMatching(
        maximumFileSizeAcrossAllFilesOverAllSides
      ))

    logger.debug(
      s"Minimum match window size across all files over all sides: $minimumWindowSizeAcrossAllFilesOverAllSides"
    )
    logger.debug(
      s"Minimum sure-fire match window size across all files over all sides: $minimumSureFireWindowSizeAcrossAllFilesOverAllSides"
    )
    logger.debug(
      s"Maximum match window size across all files over all sides: $maximumPossibleMatchSize"
    )
    logger.debug(
      s"File sizes across all files over all sides: $fileSizes"
    )

    type SectionsSeen = RangedSeq[Section[Element], Int]

    type MatchedSections = MultiDict[Section[Element], Match[Section[Element]]]

    // TODO: introduce a `GeneralMatch` type alias instead of
    // `Match[Section[Element]]`?
    type PairwiseMatch = Match.BaseAndLeft[Section[Element]] |
      Match.BaseAndRight[Section[Element]] |
      Match.LeftAndRight[Section[Element]]

    extension (pairwiseMatch: PairwiseMatch)
      def evadesRediscoveryBySearch: Boolean =
        def evadesRediscoveryBySearch(sources: Sources[Path, Element])(
            section: Section[Element]
        ): Boolean =
          val path = sources.pathFor(section)
          val minimumPossibleMatchSizeAtPath =
            minimumMatchSize max thresholdSizeForMatching(
              sources.filesByPath(path).size
            )

          section.size < minimumPossibleMatchSizeAtPath
        end evadesRediscoveryBySearch

        pairwiseMatch match
          case Match.BaseAndLeft(baseSection, leftSection) =>
            evadesRediscoveryBySearch(base)(
              baseSection
            ) && evadesRediscoveryBySearch(left)(leftSection)
          case Match.BaseAndRight(baseSection, rightSection) =>
            evadesRediscoveryBySearch(base)(
              baseSection
            ) && evadesRediscoveryBySearch(right)(rightSection)
          case Match.LeftAndRight(leftSection, rightSection) =>
            evadesRediscoveryBySearch(left)(
              leftSection
            ) && evadesRediscoveryBySearch(right)(rightSection)
        end match

    val tiebreakContentSamplingLimit = 10

    object MatchesAndTheirSections:
      private lazy val empty = MatchesAndTheirSections(
        baseSectionsByPath = Map.empty,
        leftSectionsByPath = Map.empty,
        rightSectionsByPath = Map.empty,
        sectionsAndTheirMatches = MultiDict.empty
      )

      private val rollingHashFactoryCache: Cache[Int, RollingHash.Factory] =
        Caffeine.newBuilder().build()

      def sizeOf(aMatch: PairwiseMatch): Int = aMatch match
        case Match.BaseAndLeft(baseSection, _)  => baseSection.size
        case Match.BaseAndRight(baseSection, _) => baseSection.size
        case Match.LeftAndRight(leftSection, _) => leftSection.size

      // TODO - move this to the actual class, by analogy with
      // `withAllSmallFryMatches`...
      def withAllMatchesOfAtLeastTheSureFireWindowSize()
          : MatchesAndTheirSections =
        Using(
          progressRecording.newSession(
            label = "Minimum match size considered:",
            maximumProgress = maximumPossibleMatchSize
          )(initialProgress = maximumPossibleMatchSize)
        ) { progressRecordingSession =>
          withAllMatchesOfAtLeastTheSureFireWindowSize(
            matchesAndTheirSections = empty,
            looseExclusiveUpperBoundOnMaximumMatchSize =
              1 + maximumPossibleMatchSize,
            progressRecordingSession = progressRecordingSession
          )
        }.get
      end withAllMatchesOfAtLeastTheSureFireWindowSize

      @tailrec
      private def withAllMatchesOfAtLeastTheSureFireWindowSize(
          matchesAndTheirSections: MatchesAndTheirSections,
          looseExclusiveUpperBoundOnMaximumMatchSize: Int,
          progressRecordingSession: ProgressRecordingSession
      ): MatchesAndTheirSections =
        // Essentially a binary chop algorithm, but using
        // `fallbackImprovedState` to track the best solution.
        @tailrec
        def keepTryingToImproveThis(
            bestMatchSize: Int,
            looseExclusiveUpperBoundOnMaximumMatchSize: Int,
            guessAtOptimalMatchSize: Option[Int],
            fallbackImprovedState: MatchesAndTheirSections
        ): MatchesAndTheirSections =
          require(
            bestMatchSize < looseExclusiveUpperBoundOnMaximumMatchSize
          )

          guessAtOptimalMatchSize.foreach(guess =>
            require(looseExclusiveUpperBoundOnMaximumMatchSize > guess)
          )

          if 1 + bestMatchSize < looseExclusiveUpperBoundOnMaximumMatchSize
          then
            // There is at least one candidate window size greater than
            // `bestMatchSize`...
            val candidateWindowSize = guessAtOptimalMatchSize
              .getOrElse {
                // Speculative optimisation - if the largest file was
                // modified on just one side (or coincidentally added as
                // exact duplicates on both sides), then we may as well go
                // straight to it to avoid the cost of working back up to
                // that matching size with lots of overlapping matches.
                val potentialFullMatchSize = for
                  largestPertinentFileSize <- fileSizes
                    .rangeFrom(bestMatchSize)
                    .rangeTo(looseExclusiveUpperBoundOnMaximumMatchSize)
                    .lastOption
                  largestFileMightBeUnchangedOnOneSideOrCoincidentallyAddedAsInDuplicate =
                    1 < fileSizes.get(largestPertinentFileSize)
                  if largestFileMightBeUnchangedOnOneSideOrCoincidentallyAddedAsInDuplicate
                yield largestPertinentFileSize

                val bisectedSize =
                  (bestMatchSize + looseExclusiveUpperBoundOnMaximumMatchSize) / 2
                potentialFullMatchSize.fold(ifEmpty = bisectedSize)(
                  _ max bisectedSize
                )
              }

            val MatchingResult(
              stateAfterTryingCandidate,
              numberOfMatchesForTheGivenWindowSize,
              estimatedWindowSizeForOptimalMatch
            ) = matchesAndTheirSections.matchesForWindowSize(
              candidateWindowSize
            )

            estimatedWindowSizeForOptimalMatch match
              case None =>
                // Failed to improve the match size, try again with the
                // contracted upper bound.
                keepTryingToImproveThis(
                  bestMatchSize,
                  looseExclusiveUpperBoundOnMaximumMatchSize =
                    candidateWindowSize,
                  guessAtOptimalMatchSize = None,
                  fallbackImprovedState
                )
              case Some(estimate)
                  if estimate == candidateWindowSize || 1 == numberOfMatchesForTheGivenWindowSize =>
                // Found the optimal solution; try searching for the next
                // lowest optimal size. NOTE: this won't pick up multiple
                // distinct optimal matches, see below.
                logger.debug(
                  s"Search has found an optimal match at window size: $candidateWindowSize, number of matches is: $numberOfMatchesForTheGivenWindowSize, restarting search to look for smaller matches."
                )
                progressRecordingSession.upTo(candidateWindowSize)
                withAllMatchesOfAtLeastTheSureFireWindowSize(
                  stateAfterTryingCandidate,
                  looseExclusiveUpperBoundOnMaximumMatchSize =
                    candidateWindowSize,
                  progressRecordingSession
                )
              case Some(estimate) =>
                // We have an improvement, move the lower bound up and note
                // the improved state.

                if looseExclusiveUpperBoundOnMaximumMatchSize > estimate then
                  logger.debug(
                    s"Search has found an improved match at window size: $candidateWindowSize, number of matches is: $numberOfMatchesForTheGivenWindowSize, looking for a more optimal match with estimated window size of: $estimate."
                  )
                  keepTryingToImproveThis(
                    bestMatchSize = candidateWindowSize,
                    looseExclusiveUpperBoundOnMaximumMatchSize,
                    guessAtOptimalMatchSize = Some(estimate),
                    fallbackImprovedState = stateAfterTryingCandidate
                  )
                else
                  logger.debug(
                    s"Search has found an improved match at window size: $candidateWindowSize, number of matches is: $numberOfMatchesForTheGivenWindowSize, looking for a more optimal match."
                  )
                  keepTryingToImproveThis(
                    bestMatchSize = candidateWindowSize,
                    looseExclusiveUpperBoundOnMaximumMatchSize,
                    guessAtOptimalMatchSize = None,
                    fallbackImprovedState = stateAfterTryingCandidate
                  )
                end if
            end match
          else if minimumSureFireWindowSizeAcrossAllFilesOverAllSides == looseExclusiveUpperBoundOnMaximumMatchSize
          then
            // There is nowhere left to search.
            logger.debug(
              s"Search for matches whose size is no less than the sure-fire match window size of: $minimumSureFireWindowSizeAcrossAllFilesOverAllSides has terminated; results are:\n${pprintCustomised(fallbackImprovedState)}"
            )
            progressRecordingSession.upTo(
              minimumSureFireWindowSizeAcrossAllFilesOverAllSides
            )
            fallbackImprovedState
          else
            // The optimal matches are in the fallback improved state; try
            // searching for the next lowest optimal size. This is necessary
            // as we may have *multiple* distinct optimal matches at a given
            // window size.
            logger.debug(
              s"Search has found optimal matches at window size: $bestMatchSize, restarting search to look for smaller matches."
            )
            progressRecordingSession.upTo(bestMatchSize)
            withAllMatchesOfAtLeastTheSureFireWindowSize(
              fallbackImprovedState,
              looseExclusiveUpperBoundOnMaximumMatchSize = bestMatchSize,
              progressRecordingSession
            )
          end if
        end keepTryingToImproveThis

        keepTryingToImproveThis(
          bestMatchSize =
            minimumSureFireWindowSizeAcrossAllFilesOverAllSides - 1,
          looseExclusiveUpperBoundOnMaximumMatchSize,
          guessAtOptimalMatchSize = None,
          fallbackImprovedState = matchesAndTheirSections
        )
      end withAllMatchesOfAtLeastTheSureFireWindowSize

      private def subsumesSection(
          side: Sources[Path, Element],
          sectionsByPath: Map[Path, SectionsSeen]
      )(section: Section[Element]): Boolean =
        sectionsByPath
          .get(side.pathFor(section))
          .fold(ifEmpty = false)(
            _.filterIncludes(section.closedOpenInterval).exists(_ != section)
          )

      private def subsumingPairwiseMatches(
          sectionsAndTheirMatches: MatchedSections
      )(
          side: Sources[Path, Element],
          sectionsByPath: Map[Path, SectionsSeen]
      )(section: Section[Element]): Set[PairwiseMatch] =
        subsumingMatches(sectionsAndTheirMatches)(side, sectionsByPath)(section)
          .collect {
            case baseAndLeft: Match.BaseAndLeft[Section[Element]] =>
              baseAndLeft: PairwiseMatch
            case baseAndRight: Match.BaseAndRight[Section[Element]] =>
              baseAndRight: PairwiseMatch
            case leftAndRight: Match.LeftAndRight[Section[Element]] =>
              leftAndRight: PairwiseMatch
          }

      private def subsumingMatches(
          sectionsAndTheirMatches: MatchedSections
      )(
          side: Sources[Path, Element],
          sectionsByPath: Map[Path, SectionsSeen]
      )(section: Section[Element]): Set[Match[Section[Element]]] =
        sectionsByPath
          .get(side.pathFor(section))
          .fold(ifEmpty = Set.empty)(
            _.filterIncludes(section.closedOpenInterval)
              .filter(_ != section)
              .flatMap(sectionsAndTheirMatches.get)
              // NOTE: convert to a set at this point as we expect sections to
              // be duplicated when involved in ambiguous matches.
              .toSet
          )

      private def overlapsOrIsSubsumedBySection(
          side: Sources[Path, Element],
          sectionsByPath: Map[Path, SectionsSeen]
      )(section: Section[Element]): Boolean =
        sectionsByPath
          .get(side.pathFor(section))
          .fold(ifEmpty = false)(
            _.filterOverlaps(section.closedOpenInterval)
              // Subsuming sections are considered to be overlapping by the
              // implementation of `SectionSeen.filterOverlaps`, so use an
              // existence check with a nuanced predicate.
              .exists(candidateSection =>
                candidateSection.startOffset > section.startOffset || candidateSection.onePastEndOffset < section.onePastEndOffset
              )
          )

      private def withSection(
          side: Sources[Path, Element],
          sectionsByPath: Map[Path, SectionsSeen]
      )(
          section: Section[Element]
      ): Map[Path, SectionsSeen] =
        sectionsByPath.updatedWith(
          side.pathFor(section)
        ) {
          case Some(sections) =>
            // Allow the same section to be added more than once, on behalf of
            // ambiguous matches.
            Some(sections + section)
          case None =>
            Some(
              // NOTE: don't use `Ordering[Int]` as while that is valid, it will
              // pull in a Cats `Order[Int]` which round-trips back to an
              // `Ordering`. That makes profiling difficult.
              RangedSeq(section)(_.closedOpenInterval, Ordering.Int)
            )
        }
      end withSection

      // When the window size used to calculate matches is lower than the
      // optimal match size, overlapping matches will be made that cover the
      // elements of the optimal match. Estimate the size of the optimal match
      // by coalescing the overlaps.
      private def estimateOptimalMatchSize(
          matches: collection.Set[Match[Section[Element]]]
      ): Option[Int] =
        // Deconstruct a throwaway instance of `MatchesAndTheirSections` made
        // from just `matches` as a quick-and-dirty way of organising the
        // matches' sections.
        val MatchesAndTheirSections(
          baseSectionsByPath,
          leftSectionsByPath,
          rightSectionsByPath,
          _
        ) = matches.foldLeft(empty)(_.withMatch(_))

        val sectionsSeenOnAllPathsAcrossAllSides =
          baseSectionsByPath.values ++ leftSectionsByPath.values ++ rightSectionsByPath.values

        sectionsSeenOnAllPathsAcrossAllSides
          .flatMap(maximumSizeOfCoalescedSections)
          .maxOption
      end estimateOptimalMatchSize

      // Coalesces runs of overlapping sections together and reports the size of
      // the largest coalescence.
      private def maximumSizeOfCoalescedSections(
          sectionsSeen: SectionsSeen
      ): Option[Int] =
        val sectionsInOrderOfStartOffset = sectionsSeen.iterator.toSeq

        case class PutativeCoalescence(
            startOffset: Int,
            onePastEndOffset: Int
        )

        @tailrec
        def maximumSizeOfCoalescedSections(
            sections: Seq[Section[Element]],
            putativeCoalescence: Option[PutativeCoalescence],
            partialResult: Option[Int]
        ): Option[Int] =
          if sections.isEmpty then
            (partialResult ++ putativeCoalescence.map {
              case PutativeCoalescence(startOffset, onePastEndIndex) =>
                onePastEndIndex - startOffset
            }).maxOption
          else
            val head = sections.head

            putativeCoalescence match
              case Some(
                    PutativeCoalescence(startOffset, onePastEndOffset)
                  ) =>
                assume(head.startOffset >= startOffset)

                if head.startOffset > onePastEndOffset then
                  // The head section neither overlaps nor abuts the
                  // putative coalescence, so tally the size of the now
                  // finalised coalescence and start a new one that
                  // encompasses just the head section.
                  val size = onePastEndOffset - startOffset

                  maximumSizeOfCoalescedSections(
                    sections.tail,
                    putativeCoalescence = Some(
                      PutativeCoalescence(
                        head.startOffset,
                        head.onePastEndOffset
                      )
                    ),
                    partialResult =
                      partialResult.map(_ max size).orElse(Some(size))
                  )
                else
                  // The head section extends the putative coalescence.
                  maximumSizeOfCoalescedSections(
                    sections.tail,
                    putativeCoalescence = Some(
                      PutativeCoalescence(
                        startOffset,
                        head.onePastEndOffset max onePastEndOffset
                      )
                    ),
                    partialResult = partialResult
                  )
                end if

              case None =>
                // Start a new putative coalescence that encompasses just
                // the head section.
                maximumSizeOfCoalescedSections(
                  sections.tail,
                  putativeCoalescence = Some(
                    PutativeCoalescence(
                      head.startOffset,
                      head.onePastEndOffset
                    )
                  ),
                  partialResult = partialResult
                )
            end match
          end if
        end maximumSizeOfCoalescedSections

        maximumSizeOfCoalescedSections(
          sectionsInOrderOfStartOffset,
          putativeCoalescence = None,
          partialResult = None
        )
      end maximumSizeOfCoalescedSections

      // Breaks up a section by eating into it with smaller bites, yielding the
      // fragment sections.
      private def eatIntoSection(
          side: Sources[Path, Element],
          bites: collection.Set[Section[Element]]
      )(
          section: Section[Element]
      ): Seq[Section[Element]] =
        // TODO: can this be made to eliminate overlaps too?

        enum BiteEdge:
          case Start(startOffset: Int)
          case End(onePastEndOffset: Int)
        end BiteEdge

        val biteEdges: Seq[BiteEdge] = bites.toSeq
          .flatMap { biteSection =>
            Seq(
              BiteEdge.Start(biteSection.startOffset),
              BiteEdge.End(biteSection.onePastEndOffset)
            )
          }
          .sortWith {
            case (
                  BiteEdge.Start(startOffset),
                  BiteEdge.End(onePastEndOffset)
                ) =>
              // Bites should have positive width, so that way a start comes
              // strictly before the end of its bite.
              startOffset < onePastEndOffset
            case (
                  BiteEdge.End(onePastEndOffset),
                  BiteEdge.Start(startOffset)
                ) =>
              // If two bites abut, then the end position of the preceding bite
              // will be equal to the start position of the following bite.
              onePastEndOffset <= startOffset
            case (
                  BiteEdge.Start(firstStartOffset),
                  BiteEdge.Start(secondStartOffset)
                ) =>
              // Bites should have positive width, so this is a strict equality.
              firstStartOffset < secondStartOffset
            case (
                  BiteEdge.End(firstOnePastEndOffset),
                  BiteEdge.End(secondOnePastEndOffset)
                ) =>
              // Bites should have positive width, so this is a strict equality.
              firstOnePastEndOffset < secondOnePastEndOffset
          }

        val mealOnePastEndOffset: Int = section.onePastEndOffset

        val path = side.pathFor(section)

        case class State(
            mealStartOffset: Int,
            biteDepth: Int
        ):
          @tailrec
          final def apply(
              biteEdges: Seq[BiteEdge],
              fragments: Vector[Section[Element]]
          ): Vector[Section[Element]] =
            biteEdges match
              case Seq() =>
                if mealOnePastEndOffset > mealStartOffset then
                  fragments.appended(
                    side.section(path)(
                      startOffset = mealStartOffset,
                      size = mealOnePastEndOffset - mealStartOffset
                    )
                  )
                else fragments
              case Seq(BiteEdge.Start(startOffset), remainingBiteEdges*) =>
                require(mealOnePastEndOffset > startOffset)
                val guardedStartOffset = startOffset max mealStartOffset

                this
                  .copy(
                    mealStartOffset = guardedStartOffset,
                    biteDepth = 1 + biteDepth
                  )
                  .apply(
                    remainingBiteEdges,
                    fragments =
                      if 0 == biteDepth && guardedStartOffset > mealStartOffset
                      then
                        fragments.appended(
                          side.section(path)(
                            startOffset = mealStartOffset,
                            size = guardedStartOffset - mealStartOffset
                          )
                        )
                      else fragments
                  )
              case Seq(BiteEdge.End(onePastEndOffset), remainingBiteEdges*) =>
                require(0 < biteDepth)

                require(mealStartOffset <= onePastEndOffset)
                val guardedOnePastEndOffset =
                  onePastEndOffset min mealOnePastEndOffset

                this
                  .copy(
                    mealStartOffset = guardedOnePastEndOffset,
                    biteDepth = biteDepth - 1
                  )
                  .apply(remainingBiteEdges, fragments)
        end State

        State(
          mealStartOffset = section.startOffset,
          biteDepth = 0
        )(biteEdges, fragments = Vector.empty)
      end eatIntoSection

      case class MatchingResult(
          matchesAndTheirSections: MatchesAndTheirSections,
          numberOfMatchesForTheGivenWindowSize: Int,
          estimatedWindowSizeForOptimalMatch: Option[Int]
      ):
      // matchesAndTheirSections.checkInvariant()
      end MatchingResult

    end MatchesAndTheirSections

    case class MatchesAndTheirSections(
        baseSectionsByPath: Map[Path, SectionsSeen],
        leftSectionsByPath: Map[Path, SectionsSeen],
        rightSectionsByPath: Map[Path, SectionsSeen],
        sectionsAndTheirMatches: MatchedSections
    ):
      import MatchesAndTheirSections.*

      private val subsumesBaseSection: Section[Element] => Boolean =
        subsumesSection(base, baseSectionsByPath)
      private val subsumesLeftSection: Section[Element] => Boolean =
        subsumesSection(left, leftSectionsByPath)
      private val subsumesRightSection: Section[Element] => Boolean =
        subsumesSection(right, rightSectionsByPath)
      private val overlapsOrIsSubsumedByBaseSection
          : Section[Element] => Boolean =
        overlapsOrIsSubsumedBySection(base, baseSectionsByPath)
      private val overlapsOrIsSubsumedByLeftSection
          : Section[Element] => Boolean =
        overlapsOrIsSubsumedBySection(left, leftSectionsByPath)
      private val overlapsOrIsSubsumedByRightSection
          : Section[Element] => Boolean =
        overlapsOrIsSubsumedBySection(right, rightSectionsByPath)
      private val withBaseSection: Section[Element] => Map[Path, SectionsSeen] =
        withSection(base, baseSectionsByPath)
      private val withLeftSection: Section[Element] => Map[Path, SectionsSeen] =
        withSection(left, leftSectionsByPath)
      private val withRightSection
          : Section[Element] => Map[Path, SectionsSeen] =
        withSection(right, rightSectionsByPath)

      def checkInvariant(): Unit =
        // Check the post-condition that all matches that involve the same
        // section must be of the same kind...
        sectionsAndTheirMatches.sets.foreach { (section, matches) =>
          try
            matches.head match
              case _: kineticmerge.core.Match.AllSides[Section[Element]] =>
                matches.tail.foreach { anotherMatch =>
                  require(
                    anotherMatch
                      .isInstanceOf[kineticmerge.core.Match.AllSides[Element]]
                  )
                }
              case _: kineticmerge.core.Match.BaseAndLeft[Section[Element]] =>
                matches.tail.foreach { anotherMatch =>
                  require(
                    anotherMatch.isInstanceOf[
                      kineticmerge.core.Match.BaseAndLeft[Element]
                    ]
                  )
                }
              case _: kineticmerge.core.Match.BaseAndRight[Section[Element]] =>
                matches.tail.foreach { anotherMatch =>
                  require(
                    anotherMatch.isInstanceOf[
                      kineticmerge.core.Match.BaseAndRight[Element]
                    ]
                  )
                }
              case _: kineticmerge.core.Match.LeftAndRight[Section[Element]] =>
                matches.tail.foreach { anotherMatch =>
                  require(
                    anotherMatch.isInstanceOf[
                      kineticmerge.core.Match.LeftAndRight[Element]
                    ]
                  )
                }
          catch
            case exception: IllegalArgumentException =>
              logger.error(
                s"Post-condition failure for section: ${pprintCustomised(section)}, this has inconsistent match kinds..."
              )
              logger.error(
                s"The first match is: ${pprintCustomised(matches.head)}."
              )
              logger.error(s"Subsequent matches are...")
              matches.tail.foreach(aMatch =>
                logger.debug(s"${pprintCustomised(aMatch)}")
              )

              throw exception
          end try
        }

      def baseSections: Set[Section[Element]] =
        baseSectionsByPath.values.flatMap(_.iterator).toSet

      def leftSections: Set[Section[Element]] =
        leftSectionsByPath.values.flatMap(_.iterator).toSet

      def rightSections: Set[Section[Element]] =
        rightSectionsByPath.values.flatMap(_.iterator).toSet

      def withAllSmallFryMatches(): MatchesAndTheirSections =
        val maximumSmallFryWindowSize =
          minimumSureFireWindowSizeAcrossAllFilesOverAllSides - 1

        Using(
          progressRecording.newSession(
            label = "Minimum match size considered:",
            maximumProgress = maximumSmallFryWindowSize
          )(initialProgress = maximumSmallFryWindowSize)
        ) { progressRecordingSession =>
          withAllSmallFryMatches(
            maximumSmallFryWindowSize,
            progressRecordingSession
          )
        }.get
      end withAllSmallFryMatches

      @tailrec
      private final def withAllSmallFryMatches(
          candidateWindowSize: Int,
          progressRecordingSession: ProgressRecordingSession
      ): MatchesAndTheirSections =
        require(
          minimumWindowSizeAcrossAllFilesOverAllSides until minimumSureFireWindowSizeAcrossAllFilesOverAllSides contains candidateWindowSize
        )

        // We will be exploring the small-fry window sizes below
        // `minimumSureFireWindowSizeAcrossAllFilesOverAllSides`; in this
        // situation, sizes below per-file thresholds lead to no matches, this
        // leads to gaps in validity as a size exceeds the largest match it
        // could participate in, but fails to meet the next highest per-file
        // threshold. Consequently, don't bother checking whether any matches
        // were found - instead, plod linearly down each window size.
        val MatchingResult(
          stateAfterTryingCandidate,
          numberOfMatchesForTheGivenWindowSize,
          _
        ) =
          this.matchesForWindowSize(candidateWindowSize)

        if candidateWindowSize > minimumWindowSizeAcrossAllFilesOverAllSides
        then
          if 0 < numberOfMatchesForTheGivenWindowSize then
            logger.debug(
              s"Search has found a match at window size: $candidateWindowSize, number of matches is: $numberOfMatchesForTheGivenWindowSize, continuing to look for smaller matches."
            )
          end if
          progressRecordingSession.upTo(candidateWindowSize)

          stateAfterTryingCandidate.withAllSmallFryMatches(
            candidateWindowSize = candidateWindowSize - 1,
            progressRecordingSession = progressRecordingSession
          )
        else
          if 0 < numberOfMatchesForTheGivenWindowSize then
            logger.debug(
              s"Search has found a match at window size: $minimumWindowSizeAcrossAllFilesOverAllSides, number of matches is: $numberOfMatchesForTheGivenWindowSize, search for matches whose size is less than the sure-fire match window size of: $minimumSureFireWindowSizeAcrossAllFilesOverAllSides has terminated; results are:\n${pprintCustomised(stateAfterTryingCandidate)}"
            )
          else
            logger.debug(
              s"Search for matches whose size is less than the sure-fire match window size of: $minimumSureFireWindowSizeAcrossAllFilesOverAllSides has terminated at minimum window size: $minimumWindowSizeAcrossAllFilesOverAllSides; results are:\n${pprintCustomised(stateAfterTryingCandidate)}"
            )
          end if
          progressRecordingSession.upTo(
            minimumWindowSizeAcrossAllFilesOverAllSides
          )

          stateAfterTryingCandidate
        end if
      end withAllSmallFryMatches

      private def matchesForWindowSize(
          windowSize: Int
      ): MatchingResult =
        require(0 < windowSize)

        val allowAmbiguousMatches =
          minimumAmbiguousMatchSize <= windowSize

        def fingerprintStartIndices(
            elements: IndexedSeq[Element]
        ): SortedMultiDict[BigInt, Int] =
          require(elements.size >= windowSize)

          val rollingHashFactory = rollingHashFactoryCache.get(
            windowSize,
            { (windowSize: Int) =>
              val fixedNumberOfBytesInElementHash =
                hashFunction.bits / JavaByte.SIZE

              val windowSizeInBytes =
                fixedNumberOfBytesInElementHash * windowSize

              new RollingHash.Factory(
                windowSize = windowSizeInBytes,
                numberOfFingerprintsToBeTaken =
                  fixedNumberOfBytesInElementHash * totalContentSize - windowSizeInBytes + 1
              )
            }
          )

          val rollingHash = rollingHashFactory()

          val accumulatingResults = mutable.SortedMultiDict.empty[BigInt, Int]

          def updateFingerprint(elementIndex: Int): Unit =
            val elementBytes =
              hashFunction
                .newHasher()
                .putObject(elements(elementIndex), summon[Funnel[Element]])
                .hash()
                .asBytes()

            elementBytes.foreach(rollingHash.pushByte)
          end updateFingerprint

          // NOTE: fingerprints are incrementally calculated walking *down* the
          // elements.
          val descendingIndices = elements.indices.reverse

          val (primingIndices, fingerprintingIndices) =
            descendingIndices.splitAt(windowSize - 1)

          // Prime to get ready for the first fingerprint...
          primingIndices.foreach(updateFingerprint)

          // ... henceforth, each pass records a new fingerprint, starting with
          // the first.
          fingerprintingIndices.foreach: fingerprintStartIndex =>
            updateFingerprint(fingerprintStartIndex)
            accumulatingResults.addOne(
              rollingHash.fingerprint -> fingerprintStartIndex
            )

          accumulatingResults
        end fingerprintStartIndices

        def fingerprintSections(
            sources: Sources[Path, Element]
        ): SortedMultiDict[PotentialMatchKey, Section[Element]] =
          sources.filesByPath
            .filter { case (_, file) =>
              val fileSize          = file.size
              val minimumWindowSize = thresholdSizeForMatching(fileSize)

              minimumWindowSize to fileSize contains windowSize
            }
            .par
            .map { case (path, file) =>
              fingerprintStartIndices(file.content).map(
                (fingerprint, fingerprintStartIndex) =>
                  val section = sources
                    .section(path)(fingerprintStartIndex, windowSize)
                  PotentialMatchKey(
                    fingerprint,
                    impliedContent = section
                  ) -> section
              )
            }
            // This isn't quite the same as flat-mapping / flattening, because
            // we want the type of the result to be a `SortedMultiDict`...
            .reduceOption(_ concat _)
            .getOrElse(SortedMultiDict.empty)
        end fingerprintSections

        val FingerprintSectionsAcrossSides(
          baseSectionsByFingerprint,
          leftSectionsByFingerprint,
          rightSectionsByFingerprint
        ) = FingerprintSectionsAcrossSides(
          baseSectionsByFingerprint = fingerprintSections(base),
          leftSectionsByFingerprint = fingerprintSections(left),
          rightSectionsByFingerprint = fingerprintSections(right)
        )

        @tailrec
        def matchingFingerprintsAcrossSides(
            baseFingerprints: Iterable[PotentialMatchKey],
            leftFingerprints: Iterable[PotentialMatchKey],
            rightFingerprints: Iterable[PotentialMatchKey],
            matches: Set[Match[Section[Element]]]
        ): MatchingResult =
          (
            baseFingerprints.headOption,
            leftFingerprints.headOption,
            rightFingerprints.headOption
          ) match
            case (Some(baseHead), Some(leftHead), Some(rightHead))
                if potentialMatchKeyOrder.eqv(
                  baseHead,
                  leftHead
                ) && potentialMatchKeyOrder.eqv(baseHead, rightHead) =>
              // Synchronised the fingerprints across all three sides...
              val potentialMatchesForSynchronisedFingerprint =
                val baseSectionsThatDoNotOverlap = LazyList
                  .from(baseSectionsByFingerprint.get(baseHead))
                  .filterNot(overlapsOrIsSubsumedByBaseSection)

                val leftSectionsThatDoNotOverlap = LazyList
                  .from(leftSectionsByFingerprint.get(leftHead))
                  .filterNot(overlapsOrIsSubsumedByLeftSection)

                val rightSectionsThatDoNotOverlap = LazyList
                  .from(rightSectionsByFingerprint.get(rightHead))
                  .filterNot(overlapsOrIsSubsumedByRightSection)

                for
                  baseSection  <- baseSectionsThatDoNotOverlap
                  leftSection  <- leftSectionsThatDoNotOverlap
                  rightSection <- rightSectionsThatDoNotOverlap
                yield (baseSection, leftSection, rightSection)
                end for
              end potentialMatchesForSynchronisedFingerprint

              potentialMatchesForSynchronisedFingerprint match
                case (
                      baseSection,
                      leftSection,
                      rightSection
                    ) #:: remainingMatches =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints.tail,
                    rightFingerprints.tail,
                    if allowAmbiguousMatches
                    then
                      matches ++ potentialMatchesForSynchronisedFingerprint
                        .map(Match.AllSides.apply)
                    else if remainingMatches.isEmpty then
                      matches + Match
                        .AllSides(baseSection, leftSection, rightSection)
                    else matches
                  )
                case _ =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints.tail,
                    rightFingerprints.tail,
                    matches
                  )
              end match

            case (Some(baseHead), Some(leftHead), Some(rightHead))
                if potentialMatchKeyOrder.eqv(
                  baseHead,
                  leftHead
                ) && potentialMatchKeyOrder.gt(
                  baseHead,
                  rightHead
                ) =>
              // Tentatively synchronised the fingerprints between the base and
              // left, need to advance the right to resolve ...
              matchingFingerprintsAcrossSides(
                baseFingerprints,
                leftFingerprints,
                rightFingerprints.tail,
                matches
              )

            case (Some(baseHead), Some(leftHead), _)
                if potentialMatchKeyOrder.eqv(baseHead, leftHead) =>
              // Synchronised the fingerprints between the base and left...
              val potentialMatchesForSynchronisedFingerprint =
                val baseSectionsThatDoNotOverlap = LazyList
                  .from(baseSectionsByFingerprint.get(baseHead))
                  .filterNot(overlapsOrIsSubsumedByBaseSection)

                val leftSectionsThatDoNotOverlap = LazyList
                  .from(leftSectionsByFingerprint.get(leftHead))
                  .filterNot(overlapsOrIsSubsumedByLeftSection)

                for
                  baseSection <- baseSectionsThatDoNotOverlap
                  leftSection <- leftSectionsThatDoNotOverlap
                yield (baseSection, leftSection)
                end for
              end potentialMatchesForSynchronisedFingerprint

              potentialMatchesForSynchronisedFingerprint match
                case (baseSection, leftSection) #:: remainingMatches =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints.tail,
                    rightFingerprints,
                    if allowAmbiguousMatches
                    then
                      matches ++ potentialMatchesForSynchronisedFingerprint
                        .map(Match.BaseAndLeft.apply)
                    else if remainingMatches.isEmpty then
                      matches + Match.BaseAndLeft(baseSection, leftSection)
                    else matches
                  )
                case _ =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints.tail,
                    rightFingerprints,
                    matches
                  )
              end match

            case (Some(baseHead), Some(leftHead), Some(rightHead))
                if potentialMatchKeyOrder.eqv(
                  baseHead,
                  rightHead
                ) && potentialMatchKeyOrder.gt(
                  baseHead,
                  leftHead
                ) =>
              // Tentatively synchronised the fingerprints between the base and
              // right, need to advance the left to resolve ...
              matchingFingerprintsAcrossSides(
                baseFingerprints,
                leftFingerprints.tail,
                rightFingerprints,
                matches
              )

            case (Some(baseHead), _, Some(rightHead))
                if potentialMatchKeyOrder.eqv(baseHead, rightHead) =>
              // Synchronised the fingerprints between the base and right...
              val potentialMatchesForSynchronisedFingerprint =
                val baseSectionsThatDoNotOverlap = LazyList
                  .from(baseSectionsByFingerprint.get(baseHead))
                  .filterNot(overlapsOrIsSubsumedByBaseSection)

                val rightSectionsThatDoNotOverlap = LazyList
                  .from(rightSectionsByFingerprint.get(rightHead))
                  .filterNot(overlapsOrIsSubsumedByRightSection)

                for
                  baseSection  <- baseSectionsThatDoNotOverlap
                  rightSection <- rightSectionsThatDoNotOverlap
                yield (baseSection, rightSection)
                end for
              end potentialMatchesForSynchronisedFingerprint

              potentialMatchesForSynchronisedFingerprint match
                case (baseSection, rightSection) #:: remainingMatches =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints,
                    rightFingerprints.tail,
                    if allowAmbiguousMatches
                    then
                      matches ++ potentialMatchesForSynchronisedFingerprint
                        .map(Match.BaseAndRight.apply)
                    else if remainingMatches.isEmpty then
                      matches + Match.BaseAndRight(baseSection, rightSection)
                    else matches
                  )
                case _ =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints.tail,
                    leftFingerprints,
                    rightFingerprints.tail,
                    matches
                  )
              end match

            case (Some(baseHead), Some(leftHead), Some(rightHead))
                if potentialMatchKeyOrder.eqv(
                  leftHead,
                  rightHead
                ) && potentialMatchKeyOrder.gt(
                  leftHead,
                  baseHead
                ) =>
              // Tentatively synchronised the fingerprints between the left and
              // right, need to advance the base to resolve ...
              matchingFingerprintsAcrossSides(
                baseFingerprints.tail,
                leftFingerprints,
                rightFingerprints,
                matches
              )

            case (_, Some(leftHead), Some(rightHead))
                if potentialMatchKeyOrder.eqv(leftHead, rightHead) =>
              // Synchronised the fingerprints between the left and right...
              val potentialMatchesForSynchronisedFingerprint =
                val leftSectionsThatDoNotOverlap = LazyList
                  .from(leftSectionsByFingerprint.get(leftHead))
                  .filterNot(overlapsOrIsSubsumedByLeftSection)

                val rightSectionsThatDoNotOverlap = LazyList
                  .from(rightSectionsByFingerprint.get(rightHead))
                  .filterNot(overlapsOrIsSubsumedByRightSection)

                for
                  leftSection  <- leftSectionsThatDoNotOverlap
                  rightSection <- rightSectionsThatDoNotOverlap
                yield (leftSection, rightSection)
                end for
              end potentialMatchesForSynchronisedFingerprint

              potentialMatchesForSynchronisedFingerprint match
                case (leftSection, rightSection) #:: remainingMatches =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints,
                    leftFingerprints.tail,
                    rightFingerprints.tail,
                    if allowAmbiguousMatches
                    then
                      matches ++ potentialMatchesForSynchronisedFingerprint
                        .map(Match.LeftAndRight.apply)
                    else if remainingMatches.isEmpty then
                      matches + Match.LeftAndRight(leftSection, rightSection)
                    else matches
                  )
                case _ =>
                  matchingFingerprintsAcrossSides(
                    baseFingerprints,
                    leftFingerprints.tail,
                    rightFingerprints.tail,
                    matches
                  )
              end match

            case (Some(baseHead), Some(leftHead), Some(rightHead)) =>
              // All the fingerprints disagree, so advance the side with the
              // minimum fingerprint to see if it can catch up and
              // synchronise...

              val minimumFingerprint =
                potentialMatchKeyOrder.min(
                  baseHead,
                  potentialMatchKeyOrder
                    .min(leftHead, rightHead)
                )

              // NOTE: just use `==` as we have already looked inside the
              // `PotentialMatchKey` instances - we just want to know which one
              // was the minimum.
              if leftHead == minimumFingerprint
              then
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints.tail,
                  rightFingerprints,
                  matches
                )
              // NOTE: just use `==` as we have already looked inside the
              // `PotentialMatchKey` instances - we just want to know which one
              // was the minimum.
              else if rightHead == minimumFingerprint
              then
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints,
                  rightFingerprints.tail,
                  matches
                )
              else
                matchingFingerprintsAcrossSides(
                  baseFingerprints.tail,
                  leftFingerprints,
                  rightFingerprints,
                  matches
                )
              end if

            case (Some(baseHead), Some(leftHead), None) =>
              // The base and left fingerprints disagree, so advance the side
              // with the minimum fingerprint to see if it can catch up and
              // synchronise...
              if potentialMatchKeyOrder.lt(baseHead, leftHead)
              then
                matchingFingerprintsAcrossSides(
                  baseFingerprints.tail,
                  leftFingerprints,
                  rightFingerprints,
                  matches
                )
              else
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints.tail,
                  rightFingerprints,
                  matches
                )

            case (Some(baseHead), None, Some(rightHead)) =>
              // The base and right fingerprints disagree, so advance the side
              // with the minimum fingerprint to see if it can catch up and
              // synchronise...
              if potentialMatchKeyOrder.lt(baseHead, rightHead)
              then
                matchingFingerprintsAcrossSides(
                  baseFingerprints.tail,
                  leftFingerprints,
                  rightFingerprints,
                  matches
                )
              else
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints,
                  rightFingerprints.tail,
                  matches
                )

            case (None, Some(leftHead), Some(rightHead)) =>
              // The left and right fingerprints disagree, so advance the side
              // with the minimum fingerprint to see if it can catch up and
              // synchronise...
              if potentialMatchKeyOrder.lt(leftHead, rightHead)
              then
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints.tail,
                  rightFingerprints,
                  matches
                )
              else
                matchingFingerprintsAcrossSides(
                  baseFingerprints,
                  leftFingerprints,
                  rightFingerprints.tail,
                  matches
                )

            case _ =>
              // There are no more opportunities to match a full triple or
              // just a pair, so this terminates the recursion.
              withMatches(matches, windowSize)
          end match
        end matchingFingerprintsAcrossSides

        matchingFingerprintsAcrossSides(
          baseSectionsByFingerprint.keySet,
          leftSectionsByFingerprint.keySet,
          rightSectionsByFingerprint.keySet,
          matches = Set.empty
        )
      end matchesForWindowSize

      private def withMatches(
          matches: collection.Set[Match[Section[Element]]],
          windowSize: Int
      ): MatchingResult =
        val (paredDownMatches, stabilized) =
          stabilize(matches, windowSize, phase = 0)

        val (
          updatedMatchesAndTheirSections,
          matchesWithoutRedundantPairwiseMatches
        ) =
          paredDownMatches
            .foldLeft(stabilized)(_ withMatch _)
            // NOTE: this looks terrible - why add all the matches in
            // unconditionally and then take out the redundant pairwise ones?
            // The answer is because the matches being added are in no
            // particular order - so we would have to add all the all-sides
            // matches first unconditionally and then vet the pairwise ones
            // afterwards.
            .withoutRedundantPairwiseMatchesIn(paredDownMatches)

        MatchingResult(
          matchesAndTheirSections = updatedMatchesAndTheirSections,
          numberOfMatchesForTheGivenWindowSize =
            matchesWithoutRedundantPairwiseMatches.size,
          estimatedWindowSizeForOptimalMatch =
            estimateOptimalMatchSize(matchesWithoutRedundantPairwiseMatches)
        )
      end withMatches

      // Cleans up the state when a putative all-sides match that would have
      // been ambiguous on one side with another all-sides match was partially
      // suppressed by a larger pairwise match. This situation results in a
      // pairwise match that shares its sections on both sides with the other
      // all-sides match; remove any such redundant pairwise matches.
      private def withoutRedundantPairwiseMatchesIn(
          matches: collection.Set[Match[Section[Element]]]
      ): (MatchesAndTheirSections, collection.Set[Match[Section[Element]]]) =
        val (redundantMatches, usefulMatches) =
          matches.partition {
            case Match.BaseAndLeft(baseSection, leftSection) =>
              sectionsAndTheirMatches
                .get(baseSection)
                .intersect(sectionsAndTheirMatches.get(leftSection))
                .exists(_.isAnAllSidesMatch)
            case Match.BaseAndRight(baseSection, rightSection) =>
              sectionsAndTheirMatches
                .get(baseSection)
                .intersect(sectionsAndTheirMatches.get(rightSection))
                .exists(_.isAnAllSidesMatch)
            case Match.LeftAndRight(leftSection, rightSection) =>
              sectionsAndTheirMatches
                .get(leftSection)
                .intersect(sectionsAndTheirMatches.get(rightSection))
                .exists(_.isAnAllSidesMatch)
            case _: Match.AllSides[Section[Element]] => false
          }
        end val

        if redundantMatches.nonEmpty then
          logger.debug(
            s"Removing redundant pairwise matches:\n${pprintCustomised(redundantMatches)} as their sections also belong to all-sides matches."
          )
        end if

        withoutTheseMatches(redundantMatches) -> usefulMatches
      end withoutRedundantPairwiseMatchesIn

      @tailrec
      private def stabilize(
          matches: collection.Set[Match[Section[Element]]],
          windowSize: Int,
          phase: Int
      ): (
          collection.Set[Match[Section[Element]]],
          MatchesAndTheirSections
      ) =
        val paredDownMatches = matches.flatMap(pareDownOrSuppressCompletely)

        val allSidesMatches = paredDownMatches.collect {
          case allSides: Match.AllSides[Section[Element]] => allSides
        }

        def pairwiseMatchesSubsumingOnBothSides(
            allSides: Match.AllSides[Section[Element]]
        ): Set[PairwiseMatch] =
          val subsumingOnBase =
            subsumingPairwiseMatches(sectionsAndTheirMatches)(
              base,
              baseSectionsByPath
            )(
              allSides.baseElement
            )
          val subsumingOnLeft =
            subsumingPairwiseMatches(sectionsAndTheirMatches)(
              left,
              leftSectionsByPath
            )(
              allSides.leftElement
            )
          val subsumingOnRight =
            subsumingPairwiseMatches(sectionsAndTheirMatches)(
              right,
              rightSectionsByPath
            )(
              allSides.rightElement
            )

          (subsumingOnBase intersect subsumingOnLeft) union (subsumingOnBase intersect subsumingOnRight) union (subsumingOnLeft intersect subsumingOnRight)
        end pairwiseMatchesSubsumingOnBothSides

        val pairwiseMatchesToBeEaten: MultiDict[
          PairwiseMatch,
          Match.AllSides[Section[Element]]
        ] =
          MultiDict.from(
            allSidesMatches.flatMap(allSides =>
              pairwiseMatchesSubsumingOnBothSides(allSides).map(
                _ -> allSides
              )
            )
          )

        val fragments = pairwiseMatchesToBeEaten.keySet.flatMap[PairwiseMatch] {
          pairwiseMatch =>
            val bites = pairwiseMatchesToBeEaten.get(pairwiseMatch)

            val fragmentsFromPairwiseMatch: Seq[PairwiseMatch] =
              pairwiseMatch match
                case Match.BaseAndLeft(baseSection, leftSection) =>
                  (eatIntoSection(base, bites.map(_.baseElement))(
                    baseSection
                  ) zip eatIntoSection(left, bites.map(_.leftElement))(
                    leftSection
                  ))
                    .map(Match.BaseAndLeft.apply)

                case Match.BaseAndRight(baseSection, rightSection) =>
                  (eatIntoSection(base, bites.map(_.baseElement))(
                    baseSection
                  ) zip eatIntoSection(right, bites.map(_.rightElement))(
                    rightSection
                  )).map(Match.BaseAndRight.apply)

                case Match.LeftAndRight(leftSection, rightSection) =>
                  (eatIntoSection(left, bites.map(_.leftElement))(
                    leftSection
                  ) zip eatIntoSection(right, bites.map(_.rightElement))(
                    rightSection
                  )).map(Match.LeftAndRight.apply)

            logger.debug(
              s"Eating into pairwise match:\n${pprintCustomised(pairwiseMatch)} on behalf of all-sides matches:\n${pprintCustomised(bites)}, resulting in fragments:\n${pprintCustomised(fragmentsFromPairwiseMatch)}."
            )

            fragmentsFromPairwiseMatch
        }

        val stabilized = fragments.isEmpty

        val withoutThePairwiseMatchesThatWereEatenInto = withoutTheseMatches(
          pairwiseMatchesToBeEaten.keySet
        )

        val paredDownFragments =
          fragments.toSeq
            .flatMap(
              withoutThePairwiseMatchesThatWereEatenInto.pareDownOrSuppressCompletely
            )
            .asInstanceOf[Seq[PairwiseMatch]]

        val updatedThis = paredDownFragments
          .foldLeft(withoutThePairwiseMatchesThatWereEatenInto)(_ withMatch _)

        if !stabilized then
          val numberOfAttempts = 1 + phase

          logger.debug(
            s"Stabilization at window size $windowSize has made $numberOfAttempts successful attempt(s) to break down larger pairwise matches into fragments, looking for more..."
          )

          updatedThis.stabilize(
            // Always start with the original matches; we anticipate that paring
            // them down may be more lenient the further we recurse.
            matches,
            windowSize,
            phase = numberOfAttempts
          )
        else (paredDownMatches, updatedThis)
        end if
      end stabilize

      private def withMatch(
          aMatch: Match[Section[Element]]
      ): MatchesAndTheirSections =
        aMatch match
          case Match.AllSides(baseSection, leftSection, rightSection) =>
            copy(
              baseSectionsByPath = withBaseSection(baseSection),
              leftSectionsByPath = withLeftSection(leftSection),
              rightSectionsByPath = withRightSection(rightSection),
              sectionsAndTheirMatches =
                sectionsAndTheirMatches + (baseSection -> aMatch) + (leftSection -> aMatch) + (rightSection -> aMatch)
            )
          case Match.BaseAndLeft(baseSection, leftSection) =>
            copy(
              baseSectionsByPath = withBaseSection(baseSection),
              leftSectionsByPath = withLeftSection(leftSection),
              sectionsAndTheirMatches =
                sectionsAndTheirMatches + (baseSection -> aMatch) + (leftSection -> aMatch)
            )
          case Match.BaseAndRight(baseSection, rightSection) =>
            copy(
              baseSectionsByPath = withBaseSection(baseSection),
              rightSectionsByPath = withRightSection(rightSection),
              sectionsAndTheirMatches =
                sectionsAndTheirMatches + (baseSection -> aMatch) + (rightSection -> aMatch)
            )
          case Match.LeftAndRight(leftSection, rightSection) =>
            copy(
              leftSectionsByPath = withLeftSection(leftSection),
              rightSectionsByPath = withRightSection(rightSection),
              sectionsAndTheirMatches =
                sectionsAndTheirMatches + (leftSection -> aMatch) + (rightSection -> aMatch)
            )
        end match
      end withMatch

      private def pareDownOrSuppressCompletely(
          aMatch: Match[Section[Element]]
      ): Option[Match[Section[Element]]] =
        aMatch match
          case Match.AllSides(baseSection, leftSection, rightSection)
              if !overlapsOrIsSubsumedByBaseSection(
                baseSection
              ) && !overlapsOrIsSubsumedByLeftSection(
                leftSection
              ) && !overlapsOrIsSubsumedByRightSection(rightSection) =>
            val subsumingOnBase =
              subsumingMatches(sectionsAndTheirMatches)(
                base,
                baseSectionsByPath
              )(
                baseSection
              )
            val subsumingOnLeft =
              subsumingMatches(sectionsAndTheirMatches)(
                left,
                leftSectionsByPath
              )(
                leftSection
              )
            val subsumingOnRight =
              subsumingMatches(sectionsAndTheirMatches)(
                right,
                rightSectionsByPath
              )(
                rightSection
              )

            val allSidesSubsumingOnLeft =
              subsumingOnLeft.filter(_.isAnAllSidesMatch)
            val allSidesSubsumingOnRight =
              subsumingOnRight.filter(_.isAnAllSidesMatch)
            val allSidesSubsumingOnBase =
              subsumingOnBase.filter(_.isAnAllSidesMatch)

            val subsumedByAnAllSidesMatchOnMoreThanOneSide =
              (allSidesSubsumingOnLeft intersect allSidesSubsumingOnRight).nonEmpty
                || (allSidesSubsumingOnBase intersect allSidesSubsumingOnLeft).nonEmpty
                || (allSidesSubsumingOnBase intersect allSidesSubsumingOnRight).nonEmpty

            if !subsumedByAnAllSidesMatchOnMoreThanOneSide then
              val subsumedBySomeMatchOnJustTheBase =
                (subsumingOnBase diff (subsumingOnLeft union subsumingOnRight)).nonEmpty
              val subsumedBySomeMatchOnJustTheLeft =
                (subsumingOnLeft diff (subsumingOnBase union subsumingOnRight)).nonEmpty
              val subsumedBySomeMatchOnJustTheRight =
                (subsumingOnRight diff (subsumingOnBase union subsumingOnLeft)).nonEmpty

              // NOTE: an all-sides match could be subsumed by *some* match on
              // just one side for two or three sides; they would be *different*
              // matches, each doing a one-sided subsumption.
              (
                subsumedBySomeMatchOnJustTheBase,
                subsumedBySomeMatchOnJustTheLeft,
                subsumedBySomeMatchOnJustTheRight
              ) match
                case (false, false, false) => Some(aMatch)
                case (true, false, false) =>
                  Option.unless(
                    subsumesLeftSection(leftSection) || subsumesRightSection(
                      rightSection
                    )
                  )(Match.LeftAndRight(leftSection, rightSection))
                case (false, true, false) =>
                  Option.unless(
                    subsumesBaseSection(baseSection) || subsumesRightSection(
                      rightSection
                    )
                  )(Match.BaseAndRight(baseSection, rightSection))
                case (false, false, true) =>
                  Option.unless(
                    subsumesBaseSection(baseSection) || subsumesLeftSection(
                      leftSection
                    )
                  )(Match.BaseAndLeft(baseSection, leftSection))
                case _ => None
              end match
            else None
            end if

          case Match.BaseAndLeft(baseSection, leftSection)
              if !overlapsOrIsSubsumedByBaseSection(
                baseSection
              ) && !overlapsOrIsSubsumedByLeftSection(
                leftSection
              ) =>
            Option.unless(
              subsumesBaseSection(baseSection) || subsumesLeftSection(
                leftSection
              )
            )(aMatch)

          case Match.BaseAndRight(baseSection, rightSection)
              if !overlapsOrIsSubsumedByBaseSection(
                baseSection
              ) && !overlapsOrIsSubsumedByRightSection(rightSection) =>
            Option.unless(
              subsumesBaseSection(baseSection) || subsumesRightSection(
                rightSection
              )
            )(aMatch)

          case Match.LeftAndRight(leftSection, rightSection)
              if !overlapsOrIsSubsumedByLeftSection(
                leftSection
              ) && !overlapsOrIsSubsumedByRightSection(rightSection) =>
            Option.unless(
              subsumesLeftSection(leftSection) || subsumesRightSection(
                rightSection
              )
            )(aMatch)

          case _ => None
        end match
      end pareDownOrSuppressCompletely

      private def withoutTheseMatches(
          matches: Iterable[Match[Section[Element]]]
      ): MatchesAndTheirSections =
        matches.foldLeft(this) {
          case (
                matchesAndTheirSections,
                allSides @ Match.AllSides(
                  baseSection,
                  leftSection,
                  rightSection
                )
              ) =>
            val basePath  = base.pathFor(baseSection)
            val leftPath  = left.pathFor(leftSection)
            val rightPath = right.pathFor(rightSection)
            matchesAndTheirSections
              .focus(_.baseSectionsByPath)
              .modify(_.updatedWith(basePath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - baseSection)
              })
              .focus(_.leftSectionsByPath)
              .modify(_.updatedWith(leftPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - leftSection)
              })
              .focus(_.rightSectionsByPath)
              .modify(_.updatedWith(rightPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - rightSection)
              })
              .focus(_.sectionsAndTheirMatches)
              .modify(
                _.remove(baseSection, allSides)
                  .remove(leftSection, allSides)
                  .remove(rightSection, allSides)
              )

          case (
                matchesAndTheirSections,
                baseAndLeft @ Match.BaseAndLeft(baseSection, leftSection)
              ) =>
            val basePath = base.pathFor(baseSection)
            val leftPath = left.pathFor(leftSection)
            matchesAndTheirSections
              .focus(_.baseSectionsByPath)
              .modify(_.updatedWith(basePath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - baseSection)
              })
              .focus(_.leftSectionsByPath)
              .modify(_.updatedWith(leftPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - leftSection)
              })
              .focus(_.sectionsAndTheirMatches)
              .modify(
                _.remove(baseSection, baseAndLeft)
                  .remove(leftSection, baseAndLeft)
              )

          case (
                matchesAndTheirSections,
                baseAndRight @ Match.BaseAndRight(baseSection, rightSection)
              ) =>
            val basePath  = base.pathFor(baseSection)
            val rightPath = right.pathFor(rightSection)
            matchesAndTheirSections
              .focus(_.baseSectionsByPath)
              .modify(_.updatedWith(basePath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - baseSection)
              })
              .focus(_.rightSectionsByPath)
              .modify(_.updatedWith(rightPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - rightSection)
              })
              .focus(_.sectionsAndTheirMatches)
              .modify(
                _.remove(baseSection, baseAndRight)
                  .remove(rightSection, baseAndRight)
              )

          case (
                matchesAndTheirSections,
                leftAndRight @ Match.LeftAndRight(leftSection, rightSection)
              ) =>
            val leftPath  = left.pathFor(leftSection)
            val rightPath = right.pathFor(rightSection)
            matchesAndTheirSections
              .focus(_.leftSectionsByPath)
              .modify(_.updatedWith(leftPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - leftSection)
              })
              .focus(_.rightSectionsByPath)
              .modify(_.updatedWith(rightPath) { case Some(sectionsSeen) =>
                Some(sectionsSeen - rightSection)
              })
              .focus(_.sectionsAndTheirMatches)
              .modify(
                _.remove(leftSection, leftAndRight)
                  .remove(rightSection, leftAndRight)
              )
        }
      end withoutTheseMatches
    end MatchesAndTheirSections

    given potentialMatchKeyOrder: Order[PotentialMatchKey] =
      Order.whenEqual(
        Order.by(_.fingerprint),
        // NOTE: need the pesky type ascription because `Order` is invariant on
        // its type parameter.
        Order.by(
          _.impliedContent.content
            .take(tiebreakContentSamplingLimit): Seq[Element]
        )
      )
    end potentialMatchKeyOrder

    // NOTE: this is subtle - this type is used as an ordered key to find
    // matches across sides; fingerprints can and do collide, so we need the
    // content as a tiebreaker. However, we don't want to have to freight the
    // content around for keys that will never match across sides - there are a
    // lot of keys involved in finding matches at low window sizes, and large
    // window sizes imply large content sizes.
    //
    // The solution is to rely on lazy evaluation semantics for ordering of
    // pairs, and to evaluate the content of the section when it's really needed
    // to break a tie on fingerprints. However, this means that when there are
    // multiple matches whose keys collide, then only one key can represent the
    // matches in a `SortedMultiDict` - so we expect to see keys whose section
    // is unrelated to some of the matches it is associated with, but is a
    // legitimate key for them nonetheless.
    case class PotentialMatchKey(
        fingerprint: BigInt,
        impliedContent: Section[Element]
    )

    case class FingerprintSectionsAcrossSides(
        baseSectionsByFingerprint: SortedMultiDict[PotentialMatchKey, Section[
          Element
        ]],
        leftSectionsByFingerprint: SortedMultiDict[PotentialMatchKey, Section[
          Element
        ]],
        rightSectionsByFingerprint: SortedMultiDict[PotentialMatchKey, Section[
          Element
        ]]
    )

    val matchesAndTheirSections =
      val withAllMatchesOfAtLeastTheSureFireWindowSize =
        MatchesAndTheirSections.withAllMatchesOfAtLeastTheSureFireWindowSize()

      if minimumSureFireWindowSizeAcrossAllFilesOverAllSides > minimumWindowSizeAcrossAllFilesOverAllSides
      then
        withAllMatchesOfAtLeastTheSureFireWindowSize
          .withAllSmallFryMatches()
      else withAllMatchesOfAtLeastTheSureFireWindowSize
      end if
    end matchesAndTheirSections

    // matchesAndTheirSections.checkInvariant()

    try
      val sectionsAndTheirMatches =
        matchesAndTheirSections.sectionsAndTheirMatches

      val baseFilesByPath =
        base.filesByPathUtilising(
          mandatorySections = matchesAndTheirSections.baseSections
        )

      // NOTE: we collect the unmatched sections from the base side and use them
      // to break up gap fills for the left- and right-sides. This gives the
      // downstream merge a chance to make last-minute matches of its own
      // between small unmatched sections that are deleted from the base and
      // their counterparts on the left or right. See
      // https://github.com/sageserpent-open/kineticMerge/issues/42 and
      // https://github.com/sageserpent-open/kineticMerge/issues/43.
      val candidateGapChunksByPath = baseFilesByPath.map { case (path, file) =>
        path -> file.sections
          .filterNot(sectionsAndTheirMatches.containsKey)
          .map(_.content)
          .toSet
      }

      val leftFilesByPath =
        left.filesByPathUtilising(
          mandatorySections = matchesAndTheirSections.leftSections,
          candidateGapChunksByPath = candidateGapChunksByPath
        )
      val rightFilesByPath =
        right.filesByPathUtilising(
          mandatorySections = matchesAndTheirSections.rightSections,
          candidateGapChunksByPath = candidateGapChunksByPath
        )

      Right(new CodeMotionAnalysis[Path, Element]:
        override def matchesFor(
            section: Section[Element]
        ): collection.Set[Match[Section[Element]]] =
          sectionsAndTheirMatches.get(section)

        override def base: Map[Path, File[Element]] = baseFilesByPath

        override def left: Map[Path, File[Element]] = leftFilesByPath

        override def right: Map[Path, File[Element]] = rightFilesByPath
      )
    catch
      // NOTE: don't convert this to use of `Try` with a subsequent `.toEither`
      // conversion. We want most flavours of exception to propagate, as they
      // are likely to be logic errors or something just as unwholesome.
      case admissibleException: AdmissibleFailure => Left(admissibleException)
    end try
  end of

  class AdmissibleFailure(message: String) extends RuntimeException(message)

  /** @param minimumMatchSize
    * @param thresholdSizeFractionForMatching
    *   A section's size must be at least this fraction of its containing file's
    *   size to qualify for matching.
    * @param minimumAmbiguousMatchSize
    * @param propagateAllExceptions
    */
  case class Configuration(
      minimumMatchSize: Int,
      thresholdSizeFractionForMatching: Double,
      minimumAmbiguousMatchSize: Int,
      progressRecording: ProgressRecording = NoProgressRecording
  ):
    require(0 <= minimumMatchSize)
    require(0 <= thresholdSizeFractionForMatching)
    require(1 >= thresholdSizeFractionForMatching)
    require(0 <= minimumAmbiguousMatchSize)
  end Configuration
end CodeMotionAnalysis
