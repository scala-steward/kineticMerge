package com.sageserpent.kineticmerge.core

import cats.{Eq, Order}
import com.sageserpent.kineticmerge.core.CodeMotionAnalysis.AdmissibleFailure
import com.sageserpent.kineticmerge.core.FirstPassMergeResult.Recording
import com.sageserpent.kineticmerge.core.LongestCommonSubsequence.Sized
import com.sageserpent.kineticmerge.core.MoveDestinationsReport.{
  AnchoredMove,
  EvaluatedMoves
}
import com.sageserpent.kineticmerge.core.merge.of as mergeOf
import com.typesafe.scalalogging.StrictLogging
import monocle.syntax.all.*

import scala.collection.immutable.MultiDict
import scala.collection.{IndexedSeqView, Searching}

object CodeMotionAnalysisExtension extends StrictLogging:

  /** Add merging capability to a [[CodeMotionAnalysis]].
    *
    * Not sure exactly where this capability should be implemented - is it
    * really a core part of the API for [[CodeMotionAnalysis]]? Hence the
    * extension as a temporary measure.
    */

  extension [Path, Element: Eq: Order](
      codeMotionAnalysis: CodeMotionAnalysis[Path, Element]
  )
    def merge: (
        Map[Path, MergeResult[Element]],
        MoveDestinationsReport[Section[Element]]
    ) =
      import codeMotionAnalysis.matchesFor

      given Eq[Section[Element]] with
        /** This is most definitely *not* [[Section.equals]] - we want to use
          * matching of content, as the sections are expected to come from
          * *different* sides. [[Section.equals]] is expected to consider
          * sections from different sides as unequal. <p>If neither section is
          * involved in a match, fall back to comparing the contents; this is
          * vital for comparing sections that would have been part of a larger
          * match if not for that match not achieving the threshold size.
          */
        override def eqv(
            lhs: Section[Element],
            rhs: Section[Element]
        ): Boolean =
          val bothBelongToTheSameMatches =
            matchesFor(lhs).intersect(matchesFor(rhs)).nonEmpty

          bothBelongToTheSameMatches || Eq[Seq[Element]]
            .eqv(lhs.content, rhs.content)
        end eqv
      end given

      given Sized[Section[Element]] = _.size

      val paths =
        codeMotionAnalysis.base.keySet ++ codeMotionAnalysis.left.keySet ++ codeMotionAnalysis.right.keySet

      def resolution(
          baseSection: Option[Section[Element]],
          leftSection: Section[Element],
          rightSection: Section[Element]
      ): Section[Element] = baseSection.fold(ifEmpty =
        // Break the symmetry - choose the left.
        leftSection
      ) { payload =>
        // Look at the content and use *exact* comparison.

        val lhsIsCompletelyUnchanged = payload.content == leftSection.content
        val rhsIsCompletelyUnchanged = payload.content == rightSection.content

        (lhsIsCompletelyUnchanged, rhsIsCompletelyUnchanged) match
          case (false, true) => leftSection
          case (true, false) => rightSection
          case _             =>
            // Break the symmetry - choose the left.
            leftSection
        end match
      }

      type SecondPassInput =
        Either[FullyMerged[Section[Element]], Recording[Section[Element]]]

      case class AggregatedInitialMergeResult(
          secondPassInputsByPath: Map[Path, SecondPassInput],
          speculativeMigrationsBySource: Map[Section[Element], ContentMigration[
            Section[Element]
          ]],
          speculativeMoveDestinations: Set[
            SpeculativeMoveDestination[Section[Element]]
          ],
          basePreservations: Set[Section[Element]],
          leftPreservations: Set[Section[Element]],
          rightPreservations: Set[Section[Element]]
      ):
        def recordContentOfFileAddedOnLeft(
            path: Path,
            leftSections: IndexedSeq[Section[Element]]
        ): AggregatedInitialMergeResult =
          this
            .focus(_.secondPassInputsByPath)
            .modify(
              _ + (path -> Left(
                FullyMerged(
                  leftSections
                )
              ))
            )
            .focus(_.speculativeMoveDestinations)
            .modify(
              leftSections.foldLeft(_)(
                _ + SpeculativeMoveDestination.Left(_)
              )
            )

        def recordContentOfFileAddedOnRight(
            path: Path,
            rightSections: IndexedSeq[Section[Element]]
        ): AggregatedInitialMergeResult =
          this
            .focus(_.secondPassInputsByPath)
            .modify(
              _ + (path -> Left(
                FullyMerged(
                  rightSections
                )
              ))
            )
            .focus(_.speculativeMoveDestinations)
            .modify(
              rightSections.foldLeft(_)(
                _ + SpeculativeMoveDestination.Right(_)
              )
            )

        def aggregate(
            path: Path,
            firstPassMergeResult: FirstPassMergeResult[Section[Element]]
        ): AggregatedInitialMergeResult =
          this
            .focus(_.secondPassInputsByPath)
            .modify(
              _ + (path -> Right(
                firstPassMergeResult.recording
              ))
            )
            .focus(_.speculativeMigrationsBySource)
            .modify(_ concat firstPassMergeResult.speculativeMigrationsBySource)
            .focus(_.speculativeMoveDestinations)
            .modify(_ union firstPassMergeResult.speculativeMoveDestinations)
            .focus(_.basePreservations)
            .modify(_ union firstPassMergeResult.basePreservations)
            .focus(_.leftPreservations)
            .modify(_ union firstPassMergeResult.leftPreservations)
            .focus(_.rightPreservations)
            .modify(_ union firstPassMergeResult.rightPreservations)
      end AggregatedInitialMergeResult

      object AggregatedInitialMergeResult:
        def empty: AggregatedInitialMergeResult = AggregatedInitialMergeResult(
          secondPassInputsByPath = Map.empty,
          speculativeMigrationsBySource = Map.empty,
          speculativeMoveDestinations = Set.empty,
          basePreservations = Set.empty,
          leftPreservations = Set.empty,
          rightPreservations = Set.empty
        )
      end AggregatedInitialMergeResult

      val AggregatedInitialMergeResult(
        secondPassInputsByPath,
        speculativeMigrationsBySource,
        speculativeMoveDestinations,
        basePreservations,
        leftPreservations,
        rightPreservations
      ) =
        paths.foldLeft(AggregatedInitialMergeResult.empty) {
          case (partialMergeResult, path) =>
            val base  = codeMotionAnalysis.base.get(path).map(_.sections)
            val left  = codeMotionAnalysis.left.get(path).map(_.sections)
            val right = codeMotionAnalysis.right.get(path).map(_.sections)

            (base, left, right) match
              case (None, Some(leftSections), None) =>
                partialMergeResult.recordContentOfFileAddedOnLeft(
                  path,
                  leftSections
                )
              case (None, None, Some(rightSections)) =>
                partialMergeResult.recordContentOfFileAddedOnRight(
                  path,
                  rightSections
                )
              case (
                    optionalBaseSections,
                    optionalLeftSections,
                    optionalRightSections
                  ) =>
                // Mix of possibilities - the file may have been added on both
                // sides, or modified on either or both sides, or deleted on one
                // side and modified on the other, or deleted on one or both
                // sides. There is also an extraneous case where there is no
                // file on any of the sides, and another extraneous case where
                // the file is on all three sides but hasn't changed.

                // Whichever is the case, merge...

                val firstPassMergeResult
                    : FirstPassMergeResult[Section[Element]] =
                  mergeOf(mergeAlgebra = FirstPassMergeResult.mergeAlgebra())(
                    base = optionalBaseSections.getOrElse(IndexedSeq.empty),
                    left = optionalLeftSections.getOrElse(IndexedSeq.empty),
                    right = optionalRightSections.getOrElse(IndexedSeq.empty)
                  )

                partialMergeResult.aggregate(path, firstPassMergeResult)
            end match
        }

      val EvaluatedMoves(
        moveDestinationsReport,
        migratedEditSuppressions,
        substitutionsByDestination,
        anchoredMoves
      ) =
        MoveDestinationsReport.evaluateSpeculativeSourcesAndDestinations(
          speculativeMigrationsBySource,
          speculativeMoveDestinations
        )(matchesFor, resolution)

      val sourceAnchors = anchoredMoves.map(_.source)
      val oppositeSideToMoveDestinationAnchors =
        anchoredMoves.map(_.oppositeSideElement)
      val moveDestinationAnchors = anchoredMoves.map(_.moveDestination)

      val changeMigrationSources =
        // TODO: this should probably be part of `EvaluatedMoves`, and perhaps
        // computed in a way that relies less on inference...
        (moveDestinationsReport.sources diff sourceAnchors).filterNot(
          candidate =>
            val moveDestinations =
              moveDestinationsReport.moveDestinationsBySources(candidate)
            moveDestinations.isDivergent || moveDestinations.coincident.nonEmpty
        )

      given sectionRunOrdering[Sequence[Item] <: Seq[Item]]
          : Ordering[Sequence[Section[Element]]] =
        Ordering.Implicits.seqOrdering(
          Ordering.by[Section[Element], IndexedSeq[Element]](_.content)(
            Ordering.Implicits.seqOrdering(summon[Eq[Element]].toOrdering)
          )
        )

      def uniqueSortedItemsFrom[Item](
          items: collection.Set[Item]
      )(using itemOrdering: Ordering[Item]): List[Item] =
        require(items.nonEmpty)

        val migratedChangesSortedByContent =
          items.toSeq.sorted(itemOrdering)

        val result =
          migratedChangesSortedByContent.tail.foldLeft(
            List(migratedChangesSortedByContent.head)
          ) { case (partialResult @ head :: _, change) =>
            if 0 == itemOrdering.compare(head, change) then partialResult
            else change :: partialResult
          }

        assume(result.nonEmpty)

        result
      end uniqueSortedItemsFrom

      enum Anchoring:
        case Predecessor
        case Successor
      end Anchoring

      def anchoredRunsUsingSelection(
          file: File[Element],
          anchor: Section[Element]
      )(
          selectAnchoredRun: IndexedSeqView[Section[Element]] => IndexedSeq[
            Section[Element]
          ]
      ) =
        val Searching.Found(indexOfSection) =
          file.searchByStartOffset(anchor.startOffset): @unchecked

        val precedingAnchoredRun =
          selectAnchoredRun(
            file.sections.view.take(indexOfSection).reverse
          ).reverse

        val succeedingAnchoredRun =
          selectAnchoredRun(file.sections.view.drop(1 + indexOfSection))

        precedingAnchoredRun -> succeedingAnchoredRun
      end anchoredRunsUsingSelection

      def anchoredRunsFromSource(
          source: Section[Element]
      ): (IndexedSeq[Section[Element]], IndexedSeq[Section[Element]]) =
        val file =
          codeMotionAnalysis.base(codeMotionAnalysis.basePathFor(source))

        anchoredRunsUsingSelection(file, anchor = source)(candidates =>
          candidates
            .takeWhile(candidate =>
              !basePreservations.contains(candidate) && !sourceAnchors
                .contains(
                  candidate
                )
            )
            .filterNot(changeMigrationSources.contains)
            // At this point, we only have a plain view rather than an indexed
            // one...
            .toIndexedSeq
        )
      end anchoredRunsFromSource

      def anchoredRunsFromSideOppositeToMoveDestination(
          moveDestinationSide: Side,
          oppositeSideSection: Section[Element]
      ): (IndexedSeq[Section[Element]], IndexedSeq[Section[Element]]) =
        val file = moveDestinationSide match
          case Side.Left =>
            codeMotionAnalysis.right(
              codeMotionAnalysis.rightPathFor(oppositeSideSection)
            )
          case Side.Right =>
            codeMotionAnalysis.left(
              codeMotionAnalysis.leftPathFor(oppositeSideSection)
            )

        anchoredRunsUsingSelection(file, anchor = oppositeSideSection)(
          candidates =>
            candidates
              .takeWhile(candidate =>
                (moveDestinationSide match
                  case Side.Left  => !rightPreservations.contains(candidate)
                  case Side.Right => !leftPreservations.contains(candidate)
                ) && !oppositeSideToMoveDestinationAnchors.contains(candidate)
              )
              .filterNot(migratedEditSuppressions.contains)
              // At this point, we only have a plain view rather than an indexed
              // one...
              .toIndexedSeq
        )
      end anchoredRunsFromSideOppositeToMoveDestination

      def anchoredRunsFromModeDestinationSide(
          moveDestinationSide: Side,
          moveDestination: Section[Element]
      ): (IndexedSeq[Section[Element]], IndexedSeq[Section[Element]]) =
        val file = moveDestinationSide match
          case Side.Left =>
            codeMotionAnalysis.left(
              codeMotionAnalysis.leftPathFor(moveDestination)
            )
          case Side.Right =>
            codeMotionAnalysis.right(
              codeMotionAnalysis.rightPathFor(moveDestination)
            )

        anchoredRunsUsingSelection(file, anchor = moveDestination)(candidates =>
          candidates
            .takeWhile(candidate =>
              (moveDestinationSide match
                case Side.Left  => !leftPreservations.contains(candidate)
                case Side.Right => !rightPreservations.contains(candidate)
              ) && !moveDestinationAnchors.contains(
                candidate
              )
            )
            // At this point, we only have a plain view rather than an indexed
            // one...
            .toIndexedSeq
        )
      end anchoredRunsFromModeDestinationSide

      case class MergedAnchoredRuns(
          precedingMerge: IndexedSeq[Section[Element]],
          succeedingMerge: IndexedSeq[Section[Element]],
          anchoredRunSuppressions: Set[Section[Element]]
      )

      val secondPassMergeAlgebra =
        SecondPassMergeResult.mergeAlgebra(
          coreMergeAlgebra = MergeResult.mergeAlgebra(resolution),
          migratedEditSuppressions
        )

      def mergesFrom(
          anchoredMove: AnchoredMove[Section[Element]]
      ): MergedAnchoredRuns =
        val (precedingAnchoredRunFromSource, succeedingAnchoredRunFromSource) =
          anchoredRunsFromSource(anchoredMove.source)

        val (
          precedingAnchoredRunFromSideOppositeToMoveDestination,
          succeedingAnchoredRunFromSideOppositeToMoveDestination
        ) = anchoredRunsFromSideOppositeToMoveDestination(
          anchoredMove.moveDestinationSide,
          anchoredMove.oppositeSideElement
        )

        val (
          precedingAnchoredRunFromMoveDestinationSide,
          succeedingAnchoredRunFromMoveDestinationSide
        ) = anchoredRunsFromModeDestinationSide(
          anchoredMove.moveDestinationSide,
          anchoredMove.moveDestination
        )

        val anchoredRunSuppressions =
          (precedingAnchoredRunFromSideOppositeToMoveDestination
            ++ precedingAnchoredRunFromMoveDestinationSide
            ++ succeedingAnchoredRunFromSideOppositeToMoveDestination
            ++ succeedingAnchoredRunFromMoveDestinationSide).toSet

        anchoredMove.moveDestinationSide match
          case Side.Left =>
            val precedingMerge =
              mergeOf(mergeAlgebra = secondPassMergeAlgebra)(
                base = precedingAnchoredRunFromSource,
                left = precedingAnchoredRunFromMoveDestinationSide,
                right = precedingAnchoredRunFromSideOppositeToMoveDestination
              ).coreMergeResult match
                case FullyMerged(sections) => sections
                case MergedWithConflicts(leftSections, rightSections) =>
                  leftSections ++ rightSections

            val succeedingMerge =
              mergeOf(mergeAlgebra = secondPassMergeAlgebra)(
                base = succeedingAnchoredRunFromSource,
                left = succeedingAnchoredRunFromMoveDestinationSide,
                right = succeedingAnchoredRunFromSideOppositeToMoveDestination
              ).coreMergeResult match
                case FullyMerged(sections) => sections
                case MergedWithConflicts(leftSections, rightSections) =>
                  rightSections ++ leftSections

            MergedAnchoredRuns(
              precedingMerge,
              succeedingMerge,
              anchoredRunSuppressions
            )
          case Side.Right =>
            val precedingMerge =
              mergeOf(mergeAlgebra = secondPassMergeAlgebra)(
                base = precedingAnchoredRunFromSource,
                left = precedingAnchoredRunFromSideOppositeToMoveDestination,
                right = precedingAnchoredRunFromMoveDestinationSide
              ).coreMergeResult match
                case FullyMerged(sections) => sections
                case MergedWithConflicts(leftSections, rightSections) =>
                  rightSections ++ leftSections

            val succeedingMerge =
              mergeOf(mergeAlgebra = secondPassMergeAlgebra)(
                base = succeedingAnchoredRunFromSource,
                left = succeedingAnchoredRunFromSideOppositeToMoveDestination,
                right = succeedingAnchoredRunFromMoveDestinationSide
              ).coreMergeResult match
                case FullyMerged(sections) => sections
                case MergedWithConflicts(leftSections, rightSections) =>
                  leftSections ++ rightSections

            MergedAnchoredRuns(
              precedingMerge,
              succeedingMerge,
              anchoredRunSuppressions
            )
        end match
      end mergesFrom

      val (
        mergesByAnchoredMoveDestination: MultiDict[
          (Section[Element], Anchoring),
          IndexedSeq[
            Section[Element]
          ]
        ],
        anchoredRunSuppressions: Set[Section[Element]]
      ) = anchoredMoves.foldLeft(
        MultiDict.empty[(Section[Element], Anchoring), IndexedSeq[
          Section[Element]
        ]] -> Set.empty[Section[Element]]
      ) {
        case (
              (partialKeyedMerges, partialAnchoredRunSuppressions),
              anchoredMove
            ) =>
          val MergedAnchoredRuns(
            precedingMerge,
            succeedingMerge,
            anchoredRunSuppressions
          ) = mergesFrom(anchoredMove)

          // NOTE: yes, this looks horrible, but try writing it using
          // `Option.unless` with flattening, or with `Option.fold`, or with
          // filters and folds, or a pattern match. Does it look any better?
          (if precedingMerge.isEmpty && succeedingMerge.isEmpty then
             partialKeyedMerges
           else if precedingMerge.isEmpty then
             partialKeyedMerges.add(
               anchoredMove.moveDestination -> Anchoring.Predecessor,
               succeedingMerge
             )
           else if succeedingMerge.isEmpty then
             partialKeyedMerges.add(
               anchoredMove.moveDestination -> Anchoring.Successor,
               precedingMerge
             )
           else
             partialKeyedMerges
               .add(
                 anchoredMove.moveDestination -> Anchoring.Predecessor,
                 succeedingMerge
               )
               .add(
                 anchoredMove.moveDestination -> Anchoring.Successor,
                 precedingMerge
               )
          ) -> (partialAnchoredRunSuppressions union anchoredRunSuppressions)
      }

      def applyAnchoredMerges(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Section[Element]]) =
        // Apply the suppressions....

        val withSuppressions = mergeResult.transformElementsEnMasse(
          _.filterNot(anchoredRunSuppressions.contains)
        )

        // Insert the anchored migrations....

        // Plan:
        // 1. Any anchored merge should be placed right next to the anchor; if
        // there was a conflict at the same place to start with, then the merge
        // suppression is applied first, possibly resolving the conflict, then
        // the merge is spliced in.
        // 2. There has to be just one unique merge to splice in.
        // 3. If two anchors are adjacent and the predecessor shares the same
        // merge with the successor, just splice in one copy of the merge.

        extension (sections: IndexedSeq[Section[Element]])
          private def appendMigratedInsertions(
              migratedInsertions: Seq[Section[Element]]
          ): IndexedSeq[Section[Element]] =
            if migratedInsertions.nonEmpty then
              if sections.nonEmpty then
                logger.debug(
                  s"Applying migrated insertion of ${pprintCustomised(migratedInsertions)} after destination: ${pprintCustomised(sections.last)}."
                )
              else
                logger.debug(
                  s"Applying migrated insertion of ${pprintCustomised(migratedInsertions)} at the start."
                )
            end if
            sections ++ migratedInsertions

        def insertAnchoredMigrations(
            sections: IndexedSeq[Section[Element]]
        ): IndexedSeq[Section[Element]] =
          sections
            .foldLeft(
              IndexedSeq.empty[Section[Element]] -> IndexedSeq
                .empty[Section[Element]]
            ) {
              case (
                    (partialResult, deferredMigration),
                    candidateAnchorDestination
                  ) =>
                val precedingMigrationAlternatives =
                  mergesByAnchoredMoveDestination
                    .get(
                      candidateAnchorDestination -> Anchoring.Successor
                    )

                val precedingMigration =
                  Option.when(precedingMigrationAlternatives.nonEmpty) {
                    val uniqueMigrations =
                      uniqueSortedItemsFrom(precedingMigrationAlternatives)

                    uniqueMigrations match
                      case head :: Nil => head
                      case _ =>
                        throw new AdmissibleFailure(
                          s"""
                               |Multiple potential migrations before destination: $candidateAnchorDestination,
                               |these are:
                               |${uniqueMigrations
                              .map(migration => s"PRE-INSERTED: $migration")
                              .zipWithIndex
                              .map((migration, index) =>
                                s"${1 + index}. $migration"
                              )
                              .mkString("\n")}
                               |These are from ambiguous matches of anchor text with the destination.
                               |Consider setting the command line parameter `--minimum-ambiguous-match-size` to something larger than ${candidateAnchorDestination.size}.
                                """.stripMargin
                        )
                    end match
                  }

                val succeedingMigrationAlternatives =
                  mergesByAnchoredMoveDestination
                    .get(
                      candidateAnchorDestination -> Anchoring.Predecessor
                    )

                val succeedingMigration =
                  Option.when(succeedingMigrationAlternatives.nonEmpty) {
                    val uniqueMigrations =
                      uniqueSortedItemsFrom(succeedingMigrationAlternatives)

                    uniqueMigrations match
                      case head :: Nil => head
                      case _ =>
                        throw new AdmissibleFailure(
                          s"""
                               |Multiple potential migrations after destination: $candidateAnchorDestination,
                               |these are:
                               |${uniqueMigrations
                              .map(migration => s"POST-INSERTION: $migration")
                              .zipWithIndex
                              .map((migration, index) =>
                                s"${1 + index}. $migration"
                              )
                              .mkString("\n")}
                               |These are from ambiguous matches of anchor text with the destination.
                               |Consider setting the command line parameter `--minimum-ambiguous-match-size` to something larger than ${candidateAnchorDestination.size}.
                                """.stripMargin
                        )
                    end match
                  }

                precedingMigration.foreach(splice =>
                  logger.debug(
                    s"Encountered succeeding anchor destination: ${pprintCustomised(candidateAnchorDestination)} with associated preceding migration splice: ${pprintCustomised(splice)}."
                  )
                )
                succeedingMigration.foreach(splice =>
                  logger.debug(
                    s"Encountered preceding anchor destination: ${pprintCustomised(candidateAnchorDestination)} with associated following migration splice: ${pprintCustomised(splice)}."
                  )
                )

                (
                  precedingMigration,
                  succeedingMigration
                ) match
                  // NOTE: avoid use of lenses in the cases below when we
                  // already have to pattern match deeply anyway...
                  case (
                        None,
                        None
                      ) =>
                    // `candidateAnchorDestination` is not an anchor after all,
                    // so we can splice in the deferred migration from the
                    // previous preceding anchor
                    (partialResult
                      .appendMigratedInsertions(
                        deferredMigration
                      ) :+ candidateAnchorDestination) -> IndexedSeq.empty

                  case (
                        Some(precedingMigrationSplice),
                        _
                      ) =>
                    // We have encountered a succeeding anchor...
                    if sectionRunOrdering.equiv(
                        deferredMigration,
                        precedingMigrationSplice
                      )
                    then
                      // The deferred migration from the previous preceding
                      // anchor and the succeeding anchor just encountered
                      // bracket the same migration.
                      (partialResult
                        .appendMigratedInsertions(
                          deferredMigration
                        ) :+ candidateAnchorDestination) -> succeedingMigration
                        .getOrElse(IndexedSeq.empty)
                    else
                      // The deferred migration from the previous preceding
                      // anchor and the succeeding anchor each contribute their
                      // own migration.
                      (partialResult
                        .appendMigratedInsertions(
                          deferredMigration
                        )
                        .appendMigratedInsertions(
                          precedingMigrationSplice
                        ) :+ candidateAnchorDestination) -> succeedingMigration
                        .getOrElse(IndexedSeq.empty)
                    end if

                  case (
                        None,
                        Some(succeedingMigrationSplice)
                      ) =>
                    // We have encountered a preceding anchor, so the deferred
                    // migration from the previous preceding anchor can finally
                    // be added to the partial result.
                    (partialResult.appendMigratedInsertions(
                      deferredMigration
                    ) :+ candidateAnchorDestination) -> succeedingMigrationSplice
                end match
            } match
            case (partialResult, deferredMigration) =>
              partialResult.appendMigratedInsertions(deferredMigration)

        path -> withSuppressions.transformElementsEnMasse(
          insertAnchoredMigrations
        )
      end applyAnchoredMerges

      def applySubstitutions(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Section[Element]]) =
        def substituteFor(
            section: Section[Element]
        ): IndexedSeq[Section[Element]] =
          val substitutions = substitutionsByDestination.get(section)

          if substitutions.nonEmpty then
            val uniqueSubstitutions = uniqueSortedItemsFrom(substitutions)

            val substitution: IndexedSeq[Section[Element]] =
              uniqueSubstitutions match
                case head :: Nil => head
                case _ =>
                  throw new AdmissibleFailure(
                    s"""
                       |Multiple potential changes migrated to destination: $section,
                       |these are:
                       |${uniqueSubstitutions
                        .map(change =>
                          if change.isEmpty then "DELETION"
                          else s"EDIT: $change"
                        )
                        .zipWithIndex
                        .map((change, index) => s"${1 + index}. $change")
                        .mkString("\n")}
                       |These are from ambiguous matches of text with the destination.
                       |Consider setting the command line parameter `--minimum-ambiguous-match-size` to something larger than ${section.size}.
                            """.stripMargin
                  )

            if substitution.isEmpty then
              logger.debug(
                s"Applying migrated deletion to move destination: ${pprintCustomised(section)}."
              )
            else if substitution.map(_.content) != IndexedSeq(section.content)
            then
              logger.debug(
                s"Applying migrated edit into ${pprintCustomised(substitution)} to move destination: ${pprintCustomised(section)}."
              )
            end if

            substitution
          else IndexedSeq(section)
          end if
        end substituteFor

        path -> mergeResult.transformElementsEnMasse(_.flatMap(substituteFor))
      end applySubstitutions

      def explodeSections(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Element]) =
        path -> mergeResult.transformElementsEnMasse(_.flatMap(_.content))
      end explodeSections

      val secondPassMergeResultsByPath
          : Map[Path, MergeResult[Section[Element]]] =
        secondPassInputsByPath.map {
          case (path, Right(recording)) =>
            val mergedSectionsResult
                : SecondPassMergeResult[MergeResult, Section[Element]] =
              recording.playback(
                secondPassMergeAlgebra
              )

            path -> mergedSectionsResult.coreMergeResult
          case (path, Left(fullyMerged)) => path -> fullyMerged
        }

      secondPassMergeResultsByPath
        .map(applyAnchoredMerges)
        .map(applySubstitutions)
        .map(explodeSections) -> moveDestinationsReport
    end merge
  end extension
end CodeMotionAnalysisExtension
