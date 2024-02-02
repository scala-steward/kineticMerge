package com.sageserpent.kineticmerge.core

import cats.Eq
import com.sageserpent.kineticmerge.core.LongestCommonSubsequence.Contribution

import scala.annotation.tailrec

object merge:
  /** Performs a three-way merge.
    * @param base
    *   {@code left} and {@code right} are considered modified versions of this
    *   sequence.
    * @param left
    *   A modified version of {@code base}. Where there is a preservation or a
    *   coincident insertion, then this provides the representative element for
    *   the merge.
    * @param right
    *   The other modified version of {@code base}.
    * @param equality
    *   This determines whether elements are considered equal. Because it is not
    *   necessarily `==` or `eq`, this requires a preference for which of the
    *   equivalent elements to pick in a preservation of coincident insertion.
    *   That preference is to take the element from {@code left}.
    * @tparam Element
    *   What the sequences contain.
    * @return
    *   A [[Result]] representing either a full merge without conflicts or a
    *   conflicted merge, wrapped in an [[Either]]. Currently the [[Either]] is
    *   always a [[Right]], as there is no support for code motion and thus no
    *   possibility of a divergence error.
    * @note
    *   The three-way merge has an implicit precondition that the {@code base},
    *   {@code left} and {@code right} are yielded from a
    *   [[LongestCommonSubsequence]], or at least conform to the implied
    *   postcondition of calling [[LongestCommonSubsequence.of]]. Failure to
    *   meet this may result in some unspecified exception being thrown.
    *
    * @note
    *   Rules of the game: If an element is [[Contribution.Common]] to all three
    *   sides, it is preserved in its left form in the merge; the merge uses the
    *   least common subsequence as a backbone to arrange other changes around.
    *   <p><p> If an element is [[Contribution.CommonToLeftAndRightOnly]], it is
    *   coincidentally inserted on both the left and right side, so goes in its
    *   left form into the merge, extending the backbone. <p><p> If an element
    *   is either [[Contribution.CommonToBaseAndLeftOnly]] or
    *   [[Contribution.CommonToBaseAndRightOnly]], this implies a deletion or an
    *   edit on the other side, extending the backbone. <p><p> If an element is
    *   a [[Contribution.Difference]] in {@code base}, then depending on the
    *   {@code left} and {@code right} context, it may mean a coincident
    *   deletion of that element, or conflicting edits of that element, or a
    *   conflict between an edit and an outright deletion of that element.
    *   <p><p> If an element is a [[Contribution.Difference]] in {@code left} or
    *   in {@code right}, then depending on context this may mean an insertion
    *   of the element on that side, or an edit, or may be one of two
    *   conflicting elements.<p><p> Edits are always looked for if they can
    *   avoid a conflict; so for example, if a [[Contribution.Difference]] in
    *   {@code left} is available to pair off with a
    *   [[Contribution.CommonToBaseAndRightOnly]] in both a {@code base} and
    *   {@code right}, it will be taken to be a left-edit. This will not
    *   conflict with a following [[Contribution.Difference]] in {@code right}
    *   as that will be taken to be a standalone right-insertion. <p><p>Edits
    *   are greedy in that they will eagerly take successive
    *   [[Contribution.Difference]] elements to make a long edit.<p><p>However,
    *   if there is for example a [[Contribution.Difference]] sandwiched between
    *   two [[Contribution.CommonToBaseAndRightOnly]] elements in {@code right},
    *   this breaks up the left-edits to preserve the sandwich in edited form in
    *   the merge.
    */
  def of[Element](
      base: IndexedSeq[Element],
      left: IndexedSeq[Element],
      right: IndexedSeq[Element]
  )(
      equality: Eq[Element]
  ): Either[Divergence.type, Result[Element]] =
    @tailrec
    def mergeBetweenRunsOfCommonElements(
        base: Seq[Contribution[Element]],
        left: Seq[Contribution[Element]],
        right: Seq[Contribution[Element]]
    )(partialResult: Result[Element]): Result[Element] =
      (base, left, right) match
        case (
              Seq(Contribution.Common(_), baseTail*),
              Seq(Contribution.Common(leftElement), leftTail*),
              Seq(Contribution.Common(_), rightTail*)
            ) => // Preservation.
          mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
            partialResult.addCommon(leftElement)
          )

        case (
              Seq(Contribution.Difference(_), baseTail*),
              Seq(
                Contribution.CommonToLeftAndRightOnly(leftElement),
                leftTail*
              ),
              Seq(
                Contribution.CommonToLeftAndRightOnly(_),
                rightTail*
              )
            ) => // Coincident edit.
          baseTail match
            case Seq(Contribution.Difference(_), _*) =>
              // If the following element in the base would also be
              // coincidentally deleted, coalesce into a single coincident
              // edit.
              mergeBetweenRunsOfCommonElements(baseTail, left, right)(
                partialResult
              )

            case _ =>
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addCommon(leftElement)
              )
          end match

        case (
              _,
              Seq(
                Contribution.CommonToLeftAndRightOnly(leftElement),
                leftTail*
              ),
              Seq(
                Contribution.CommonToLeftAndRightOnly(_),
                rightTail*
              )
            ) => // Coincident insertion.
          mergeBetweenRunsOfCommonElements(base, leftTail, rightTail)(
            partialResult.addCommon(leftElement)
          )

        case (
              Seq(
                editedBaseElement @ Contribution.CommonToBaseAndRightOnly(_),
                baseTail*
              ),
              Seq(Contribution.Difference(leftElement), leftTail*),
              Seq(
                Contribution.CommonToBaseAndRightOnly(_),
                rightTail*
              )
            ) => // Left edit.
          baseTail -> leftTail match
            case (
                  Seq(
                    Contribution.Difference(_),
                    baseTailAfterCoincidentDeletion*
                  ),
                  Seq(Contribution.Difference(_), _*)
                ) =>
              // There is a pending coincidental deletion; process it first in
              // case it hides a subsequent left edit.
              mergeBetweenRunsOfCommonElements(
                editedBaseElement +: baseTailAfterCoincidentDeletion,
                left,
                right
              )(partialResult)

            case (
                  Seq(Contribution.CommonToBaseAndRightOnly(_), _*),
                  Seq(Contribution.Difference(_), _*)
                )
                // Guard against a coincident insertion prior to the right side
                // of a pending left edit or deletion; that would maroon the
                // latter, so we *would* coalesce the following element on the
                // left.
                if rightTail
                  .takeWhile {
                    case Contribution.CommonToBaseAndRightOnly(_) => false
                    case _                                        => true
                  }
                  .forall {
                    case Contribution.CommonToLeftAndRightOnly(_) => false
                    case _                                        => true
                  } =>
              // There is another pending left edit, so *don't* coalesce the
              // following element on the left; that then respects any
              // insertions that may be lurking on the right prior to the
              // pending left edit claiming the following element on the left.
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addCommon(leftElement)
              )

            case (_, Seq(Contribution.Difference(_), _*)) =>
              // If the following element on the left would also be inserted,
              // coalesce into a single edit.
              mergeBetweenRunsOfCommonElements(base, leftTail, right)(
                partialResult.addCommon(leftElement)
              )

            case _ =>
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addCommon(leftElement)
              )
          end match

        case (
              Seq(
                Contribution.CommonToBaseAndRightOnly(_),
                baseTail*
              ),
              _,
              Seq(
                Contribution.CommonToBaseAndRightOnly(_),
                rightTail*
              )
            ) => // Left deletion.
          mergeBetweenRunsOfCommonElements(baseTail, left, rightTail)(
            partialResult
          )

        case (
              Seq(
                editedBaseElement @ Contribution.CommonToBaseAndLeftOnly(_),
                baseTail*
              ),
              Seq(
                Contribution.CommonToBaseAndLeftOnly(_),
                leftTail*
              ),
              Seq(Contribution.Difference(rightElement), rightTail*)
            ) => // Right edit.
          baseTail -> rightTail match
            case (
                  Seq(
                    Contribution.Difference(_),
                    baseTailAfterCoincidentDeletion*
                  ),
                  Seq(Contribution.Difference(_), _*)
                ) =>
              // There is a pending coincidental deletion; process it first in
              // case it hides a subsequent right edit.
              mergeBetweenRunsOfCommonElements(
                editedBaseElement +: baseTailAfterCoincidentDeletion,
                left,
                right
              )(partialResult)

            case (
                  Seq(Contribution.CommonToBaseAndLeftOnly(_), _*),
                  Seq(Contribution.Difference(_), _*)
                )
                // Guard against a coincident insertion prior to the left side
                // of a pending right edit or deletion; that would maroon the
                // latter, so we *would* coalesce the following element on the
                // right.
                if leftTail
                  .takeWhile {
                    case Contribution.CommonToBaseAndLeftOnly(_) => false
                    case _                                       => true
                  }
                  .forall {
                    case Contribution.CommonToLeftAndRightOnly(_) => false
                    case _                                        => true
                  } =>
              // There is another pending right edit, so *don't* coalesce the
              // following element on the right; that then respects any
              // insertions that may be lurking on the left prior to the pending
              // right edit claiming the following element on the right.
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addCommon(rightElement)
              )

            case (_, Seq(Contribution.Difference(_), _*)) =>
              // If the following element on the right would also be inserted,
              // coalesce into a single edit.
              mergeBetweenRunsOfCommonElements(base, left, rightTail)(
                partialResult.addCommon(rightElement)
              )

            case _ =>
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addCommon(rightElement)
              )
          end match

        case (
              Seq(
                Contribution.CommonToBaseAndLeftOnly(_),
                baseTail*
              ),
              Seq(
                Contribution.CommonToBaseAndLeftOnly(_),
                leftTail*
              ),
              _
            ) => // Right deletion.
          mergeBetweenRunsOfCommonElements(baseTail, leftTail, right)(
            partialResult
          )

        case (
              Seq(Contribution.Difference(_), baseTail*),
              Seq(Contribution.Difference(leftElement), leftTail*),
              Seq(Contribution.Difference(rightElement), rightTail*)
            ) => // Conflict, multiple possibilities.
          baseTail match
            case Seq(Contribution.Difference(_), _*) =>
              // If the following element in the base would also be edited,
              // coalesce into a single coincident edit conflict.
              mergeBetweenRunsOfCommonElements(baseTail, left, right)(
                partialResult
              )

            case Seq(
                  Contribution.CommonToBaseAndLeftOnly(_),
                  _*
                ) => // Left edit / right deletion conflict with pending right edit.
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, right)(
                partialResult.addLeftEditConflictingWithRightDeletion(
                  leftElement
                )
              )

            case Seq(
                  Contribution.CommonToBaseAndRightOnly(_),
                  _*
                ) => // Right edit / left deletion conflict with pending left edit.
              mergeBetweenRunsOfCommonElements(baseTail, left, rightTail)(
                partialResult.addRightEditConflictingWithLeftDeletion(
                  rightElement
                )
              )

            case _ =>
              // Edit conflict.
              mergeBetweenRunsOfCommonElements(baseTail, leftTail, rightTail)(
                partialResult.addConflictingEdits(leftElement, rightElement)
              )
          end match

        case (
              Seq(Contribution.Difference(_), _*),
              Seq(Contribution.Difference(leftSection), leftTail*),
              Seq(Contribution.CommonToLeftAndRightOnly(_), _*)
            ) => // Left insertion with pending coincident edit.
          mergeBetweenRunsOfCommonElements(base, leftTail, right)(
            partialResult.addCommon(leftSection)
          )

        case (
              Seq(Contribution.Difference(_), baseTail*),
              Seq(Contribution.Difference(_), _*),
              Seq(Contribution.CommonToBaseAndRightOnly(_), _*)
            ) => // Coincident deletion with pending left edit.
          mergeBetweenRunsOfCommonElements(baseTail, left, right)(partialResult)

        case (
              Seq(Contribution.Difference(_), baseTail*),
              Seq(Contribution.Difference(leftSection), leftTail*),
              _
            ) => // Left edit / right deletion conflict.
          mergeBetweenRunsOfCommonElements(baseTail, leftTail, right)(
            partialResult.addLeftEditConflictingWithRightDeletion(leftSection)
          )

        case (
              Seq(Contribution.Difference(_), _*),
              Seq(Contribution.CommonToLeftAndRightOnly(_), _*),
              Seq(Contribution.Difference(rightSection), rightTail*)
            ) => // Right insertion with pending left coincident edit.
          mergeBetweenRunsOfCommonElements(base, left, rightTail)(
            partialResult.addCommon(rightSection)
          )

        case (
              Seq(Contribution.Difference(_), baseTail*),
              Seq(Contribution.CommonToBaseAndLeftOnly(_), _*),
              Seq(Contribution.Difference(_), _*)
            ) => // Coincident deletion with pending left edit.
          mergeBetweenRunsOfCommonElements(baseTail, left, right)(partialResult)

        case (
              Seq(Contribution.Difference(_), baseTail*),
              _,
              Seq(Contribution.Difference(rightSection), rightTail*)
            ) => // Right edit / left deletion conflict.
          mergeBetweenRunsOfCommonElements(baseTail, left, rightTail)(
            partialResult.addRightEditConflictingWithLeftDeletion(rightSection)
          )

        case (
              Seq(Contribution.Difference(_), baseTail*),
              _,
              _
            ) => // Coincident deletion.
          mergeBetweenRunsOfCommonElements(baseTail, left, right)(partialResult)

        case (
              Seq(Contribution.CommonToBaseAndLeftOnly(_), _*),
              Seq(Contribution.Difference(leftElement), leftTail*),
              Seq(Contribution.Difference(_), _*)
            ) => // Left insertion with pending right edit.
          mergeBetweenRunsOfCommonElements(base, leftTail, right)(
            partialResult.addCommon(leftElement)
          )

        case (
              Seq(Contribution.CommonToBaseAndRightOnly(_), _*),
              Seq(Contribution.Difference(_), _*),
              Seq(Contribution.Difference(rightElement), rightTail*)
            ) => // Right insertion with pending left edit.
          mergeBetweenRunsOfCommonElements(base, left, rightTail)(
            partialResult.addCommon(rightElement)
          )

        case (
              _,
              Seq(Contribution.Difference(leftElement), leftTail*),
              Seq(Contribution.Difference(rightElement), rightTail*)
            ) => // Insertion conflict.
          mergeBetweenRunsOfCommonElements(base, leftTail, rightTail)(
            partialResult.addConflictingEdits(leftElement, rightElement)
          )

        case (
              Seq(
                Contribution.Common(_) |
                Contribution.CommonToBaseAndLeftOnly(_) |
                Contribution.CommonToLeftAndRightOnly(_) |
                Contribution.CommonToBaseAndRightOnly(_),
                _*
              ) | Seq(),
              Seq(Contribution.Difference(leftElement), leftTail*),
              _
            ) => // Left insertion.
          mergeBetweenRunsOfCommonElements(base, leftTail, right)(
            partialResult.addCommon(leftElement)
          )

        case (
              Seq(
                Contribution.Common(_) |
                Contribution.CommonToBaseAndRightOnly(_) |
                Contribution.CommonToLeftAndRightOnly(_) |
                Contribution.CommonToBaseAndLeftOnly(_),
                _*
              ) | Seq(),
              _,
              Seq(Contribution.Difference(rightElement), rightTail*)
            ) => // Right insertion.
          mergeBetweenRunsOfCommonElements(base, left, rightTail)(
            partialResult.addCommon(rightElement)
          )

        case (Seq(), Seq(), Seq()) => // Terminating case!
          partialResult
      end match
    end mergeBetweenRunsOfCommonElements

    val longestCommonSubsequence =
      LongestCommonSubsequence.of(base, left, right)(equality)

    val emptyResult: Result[Element] = FullyMerged(IndexedSeq.empty)

    // TODO: for now, failure is not tolerated, but obviously that will have to
    // be accommodated - both merge conflicts and later divergences.
    Right(
      mergeBetweenRunsOfCommonElements(
        longestCommonSubsequence.base,
        longestCommonSubsequence.left,
        longestCommonSubsequence.right
      )(emptyResult)
    )
  end of

  sealed trait Result[Element]:
    def addCommon(
        commonElement: Element
    ): Result[Element] =
      this match
        case FullyMerged(elements) =>
          FullyMerged(elements :+ commonElement)
        case MergedWithConflicts(leftElements, rightElements) =>
          MergedWithConflicts(
            leftElements :+ commonElement,
            rightElements :+ commonElement
          )

    def addLeftEditConflictingWithRightDeletion(
        leftElement: Element
    ): Result[Element] =
      this match
        case FullyMerged(elements) =>
          MergedWithConflicts(
            leftElements = elements :+ leftElement,
            rightElements = elements
          )
        case MergedWithConflicts(leftElements, rightElements) =>
          MergedWithConflicts(leftElements :+ leftElement, rightElements)

    def addRightEditConflictingWithLeftDeletion(
        rightElement: Element
    ): Result[Element] =
      this match
        case FullyMerged(elements) =>
          MergedWithConflicts(
            leftElements = elements,
            rightElements = elements :+ rightElement
          )
        case MergedWithConflicts(leftElements, rightElements) =>
          MergedWithConflicts(leftElements, rightElements :+ rightElement)

    def addConflictingEdits(
        leftElement: Element,
        rightElement: Element
    ): Result[Element] =
      this match
        case FullyMerged(elements) =>
          MergedWithConflicts(
            elements :+ leftElement,
            elements :+ rightElement
          )
        case MergedWithConflicts(leftElements, rightElements) =>
          MergedWithConflicts(
            leftElements :+ leftElement,
            rightElements :+ rightElement
          )
  end Result

  case class FullyMerged[Element](elements: IndexedSeq[Element])
      extends Result[Element]

  /** @param leftElements
    *   The left hand form of the merge. Has all the clean merges, plus the left
    *   side of the conflicts.
    * @param rightElements
    *   The right hand form of the merge. Has all the clean merges, plus the
    *   right side of the conflicts.
    * @tparam Element
    */
  case class MergedWithConflicts[Element](
      leftElements: IndexedSeq[Element],
      rightElements: IndexedSeq[Element]
  ) extends Result[Element]

  // TODO: "Something went wrong!" - "What was it?"
  case object Divergence

end merge
