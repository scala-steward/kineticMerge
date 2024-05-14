package com.sageserpent.kineticmerge.core

import cats.Eq
import com.sageserpent.kineticmerge.core.merge.of as mergeOf
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.MultiDict

object CodeMotionAnalysisExtension extends StrictLogging:
  /** Add merging capability to a [[CodeMotionAnalysis]].
    *
    * Not sure exactly where this capability should be implemented - is it
    * really a core part of the API for [[CodeMotionAnalysis]]? Hence the
    * extension as a temporary measure.
    */
  extension [Path, Element](
      codeMotionAnalysis: CodeMotionAnalysis[Path, Element]
  )
    def merge(
        equality: Eq[Element]
    ): (
        Map[Path, MergeResult[Element]],
        MatchesContext[Section[Element]]#MoveDestinationsReport
    ) =
      def dominantsOf(
          section: Section[Element]
      ): collection.Set[Section[Element]] =
        codeMotionAnalysis
          .matchesFor(section)
          .map(_.dominantElement)

      /** This is most definitely *not* [[Section.equals]] - we want to compare
        * the underlying content of the dominant sections, as the sections are
        * expected to come from *different* sides. [[Section.equals]] is
        * expected to consider sections from different sides as unequal. <p>If
        * neither section is involved in a match, fall back to comparing the
        * contents; this is vital for comparing sections that would have been
        * part of a larger match if not for that match not achieving the
        * threshold size.
        */
      def sectionEqualityViaDominantsFallingBackToContentComparison(
          lhs: Section[Element],
          rhs: Section[Element]
      ): Boolean =
        val bothBelongToTheSameMatches =
          dominantsOf(lhs).intersect(dominantsOf(rhs)).nonEmpty

        bothBelongToTheSameMatches || {
          given Eq[Element] = equality

          Eq[Seq[Element]].eqv(lhs.content, rhs.content)
        }
      end sectionEqualityViaDominantsFallingBackToContentComparison

      val matchesContext = MatchesContext(
        codeMotionAnalysis.matchesFor
      )

      import matchesContext.*

      val paths =
        codeMotionAnalysis.base.keySet ++ codeMotionAnalysis.left.keySet ++ codeMotionAnalysis.right.keySet

      val (
        mergeResultsByPath,
        changesPropagatedThroughMotion,
        moveDestinationsReport,
        insertions
      ) =
        paths.foldLeft(
          Map.empty[Path, MergeResult[Section[Element]]],
          Iterable.empty[
            (
                Section[Element],
                Option[
                  Section[Element]
                ]
            )
          ],
          emptyReport,
          Set.empty[Insertion]
        ) {
          case (
                (
                  mergeResultsByPath,
                  changesPropagatedThroughMotion,
                  moveDestinationsReport,
                  insertions
                ),
                path
              ) =>
            val base  = codeMotionAnalysis.base.get(path).map(_.sections)
            val left  = codeMotionAnalysis.left.get(path).map(_.sections)
            val right = codeMotionAnalysis.right.get(path).map(_.sections)

            (base, left, right) match
              case (None, Some(leftSections), None) =>
                // File added only on the left; pass through as there is neither
                // anything to merge nor any sources of edits or deletions...
                (
                  mergeResultsByPath + (path -> FullyMerged(
                    leftSections
                  )),
                  changesPropagatedThroughMotion,
                  leftSections.foldLeft(moveDestinationsReport)(
                    _.leftMoveOf(_)
                  ),
                  insertions
                )
              case (None, None, Some(rightSections)) =>
                // File added only on the right; pass through as there is
                // neither anything to merge nor any sources of edits or
                // deletions...
                (
                  mergeResultsByPath + (path -> FullyMerged(
                    rightSections
                  )),
                  changesPropagatedThroughMotion,
                  rightSections.foldLeft(moveDestinationsReport)(
                    _.rightMoveOf(_)
                  ),
                  insertions
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
                val mergedSectionsResult
                    : MergeResultDetectingMotion[MergeResult, Section[
                      Element
                    ]] =
                  mergeOf(mergeAlgebra =
                    MergeResultDetectingMotion.mergeAlgebra(coreMergeAlgebra =
                      MergeResult.mergeAlgebra
                    )
                  )(
                    base = optionalBaseSections.getOrElse(IndexedSeq.empty),
                    left = optionalLeftSections.getOrElse(IndexedSeq.empty),
                    right = optionalRightSections.getOrElse(IndexedSeq.empty)
                  )(
                    equality =
                      sectionEqualityViaDominantsFallingBackToContentComparison,
                    elementSize = _.size
                  )

                (
                  mergeResultsByPath + (path -> mergedSectionsResult.coreMergeResult),
                  changesPropagatedThroughMotion ++ mergedSectionsResult.changesPropagatedThroughMotion,
                  moveDestinationsReport.mergeWith(
                    mergedSectionsResult.moveDestinationsReport
                  ),
                  insertions ++ mergedSectionsResult.insertions
                )
            end match
        }

      // PLAN:
      // 1. Knock out each insertion whose section is a move destination, either
      // as a left- or right-move, or as part of a coincident move. That
      // includes degenerate coincident *insertions*, too!

      val insertionsThatAreNotMoveDestinations = insertions.filter {
        case Insertion(inserted, side) =>
          val dominants = dominantsOf(inserted)
          moveDestinationsReport.moveDestinationsByDominantSet
            .get(dominants)
            .fold(ifEmpty = false)(moveDestination =>
              // May as well plod through the move destinations linearly via
              // `.exists` rather than mapping to a new set and then calling
              // `.contains`.
              side match
                case Side.Left =>
                  moveDestination.left.contains(
                    inserted
                  ) || moveDestination.coincident.exists { case (leftPart, _) =>
                    inserted == leftPart
                  }
                case Side.Right =>
                  moveDestination.right
                    .contains(inserted) || moveDestination.coincident.exists {
                    case (_, rightPart) => inserted == rightPart
                  }
            )
      }

      // 2. For each insertion, look up its path by using the sources for its
      // side.

      // 3. Use the path to locate the inserted section in its file on the same
      // side (not the base) - this yields zero, one or two neighbouring
      // sections. Do a binary search on the start index of the inserted section
      // to get it relative position in the file.

      // 4. Knock out neighbouring sections that not sources of move
      // destinations on the opposite side of the inserted section, or are
      // divergent. This will include neighbouring sections that are
      // destinations themselves. The resulting neighbours therefore haven't
      // moved on the side of the inserted section.

      // 5. Knock out insertions that don't have any anchors after the previous
      // step.

      // 6. Determine the destination of the neighbour on the other side of the
      // move and taking into account whether it is a predecessor or successor
      // of the inserted section, use a binary search to find the migrated
      // insertion point in the merge at the given path, possibly on both sides
      // of a conflicted merge.

      // 7. Build a map of paths to multimaps, where each multimap has keys that
      // either:
      // a) an insertion point into a clean merge or
      // b) insertion points into either side of a conflicted merge.
      // The values of each multimap are the inserted sections that need to be
      // migrated. The keys have an ordering on insertion points that is valid
      // for both sides.

      // 8. This leaves a set of remaining inserted sections that have to be be
      // removed from their original locations, and the path to migration
      // multimap thing...

      def migrateAnchoredInsertions(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Section[Element]]) =
        // Look at the migration multimap associated with the path and insert
        // the migrated sections (if there is only one non-conflicting entry) in
        // order of their insertion points on each side. Need to take into
        // account that the migration point moves as insertions are made!

        // Also need to remove any sections that are in the set of insertions
        // being migrated - do this when traversing up the merged results,
        // removing any migrated section in its original location prior to
        // inserting one from the multimap.

        ???

      val potentialValidDestinationsForPropagatingChangesTo =
        moveDestinationsReport.moveDestinationsByDominantSet.values
          .filterNot(moveDestinations =>
            moveDestinations.isDegenerate || moveDestinations.isDivergent
          )
          .flatMap(moveDestinations =>
            // NOTE: coincident move destinations can't pick up edits as there
            // would be no side to contribute the edit; instead, both of them
            // would contribute a move.
            moveDestinations.left ++ moveDestinations.right
          )
          .toSet

      val vettedChangesPropagatedThroughMotion =
        MultiDict.from(changesPropagatedThroughMotion.filter {
          case (potentialDestination, _) =>
            potentialValidDestinationsForPropagatingChangesTo.contains(
              potentialDestination
            )
        })

      def substitutePropagatedChangesOrDominants(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Section[Element]]) =
        def substituteFor(section: Section[Element]): Option[Section[Element]] =
          val propagatedChanges: collection.Set[Option[Section[Element]]] =
            vettedChangesPropagatedThroughMotion.get(section)
          val propagatedChange: Option[Option[Section[Element]]] =
            if 1 >= propagatedChanges.size then propagatedChanges.headOption
            else
              throw new RuntimeException(
                s"""
                |Multiple potential changes propagated to destination: $section,
                |these are:
                |${propagatedChanges
                    .map(
                      _.fold(ifEmpty = "DELETION")(edit => s"EDIT: $edit")
                    )
                    .zipWithIndex
                    .map((change, index) => s"${1 + index}. $change")
                    .mkString("\n")}
                |These are from ambiguous matches of text with the destination.
                |Consider setting the command line parameter `--minimum-ambiguous-match-size` to something larger than ${section.size}.
                    """.stripMargin
              )

          // If we do have a propagated change, then there is no need to look
          // for the dominant - either the section was deleted or edited;
          // matched sections are not considered as edit candidates.
          propagatedChange.fold {
            val dominants = dominantsOf(section)

            Some(
              if dominants.isEmpty then section
              else
                // NASTY HACK: this is hokey, but essentially correct - if we
                // have ambiguous matches leading to multiple dominants, then
                // they're all just as good in terms of their content. So just
                // choose any one.
                dominants.head
            )
          }(
            _.fold(
              // Moved section was deleted...
              ifEmpty =
                logger.debug(
                  s"Applying propagated deletion to move destination: ${pprintCustomised(section)}."
                )
                None
            )(
              // Moved section was edited...
              { edit =>
                logger.debug(
                  s"Applying propagated edit into ${pprintCustomised(edit)} to move destination: ${pprintCustomised(section)}."
                )
                Some(edit)
              }
            )
          )

        end substituteFor

        path -> (mergeResult match
          case FullyMerged(sections) =>
            FullyMerged(elements = sections.flatMap(substituteFor))
          case MergedWithConflicts(leftSections, rightSections) =>
            MergedWithConflicts(
              leftSections.flatMap(substituteFor),
              rightSections.flatMap(substituteFor)
            )
        )
      end substitutePropagatedChangesOrDominants

      def explodeSections(
          path: Path,
          mergeResult: MergeResult[Section[Element]]
      ): (Path, MergeResult[Element]) =
        path -> (mergeResult match
          case FullyMerged(sections) =>
            FullyMerged(elements = sections.flatMap(_.content))
          case MergedWithConflicts(leftSections, rightSections) =>
            val leftElements  = leftSections.flatMap(_.content)
            val rightElements = rightSections.flatMap(_.content)

            // Just in case the conflict was resolved by the propagated
            // changes...
            if leftElements.corresponds(
                rightElements
              )(equality.eqv)
            then FullyMerged(leftElements)
            else
              MergedWithConflicts(
                leftElements,
                rightElements
              )
            end if
        )
      end explodeSections

      mergeResultsByPath
        .map(migrateAnchoredInsertions)
        .map(substitutePropagatedChangesOrDominants)
        .map(explodeSections) -> moveDestinationsReport
    end merge
  end extension
end CodeMotionAnalysisExtension
