package com.sageserpent.kineticmerge.core

import cats.Eq
import com.sageserpent.kineticmerge.core.merge.*

object CodeMotionAnalysisExtension:
  /** Add merging capability to a [[CodeMotionAnalysis]].
    *
    * Not sure exactly where this capability should be implemented - is it
    * really a core part of the API for [[CodeMotionAnalysis]]? Hence the
    * extension as a temporary measure.
    */
  extension [Path, Element](
      codeMotionAnalysis: CodeMotionAnalysis[Path, Element]
  )
    def mergeAt(path: Path)(
        equality: Eq[Element]
    ): Either[Divergence.type, MergeResult[Element]] =
      // TODO: amongst other things, need to convert the sections to underlying
      // content, but irrespective of what side contributed the section. What
      // about using the match to get the dominant section?

      // The base contribution is optional.
      val baseSections: IndexedSeq[Section[Element]] =
        codeMotionAnalysis.base
          .get(path)
          .fold(ifEmpty = Vector.empty)(_.sections)

      // For now, the left and right contributions are mandatory - we are
      // merging changes made in parallel on the same path, not handling
      // addition or deletion.
      val leftSections  = codeMotionAnalysis.left(path).sections
      val rightSections = codeMotionAnalysis.right(path).sections

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

      val mergedSectionsResult
          : Either[merge.Divergence.type, MergeResult[Section[Element]]] =
        merge.of(mergeAlgebra = MergeResult.mergeAlgebra)(
          base = baseSections,
          left = leftSections,
          right = rightSections
        )(equality = sectionEqualityViaDominantsFallingBackToContentComparison)

      def elementsOf(section: Section[Element]): IndexedSeq[Element] =
        // NOTE: here is probably where the mapping from section to edit or
        // deletion should be applied. If a deletion is applied, obviously a
        // dominant doesn't need to be found, and an edit should be a
        // non-matched section, so again, don't bother looking for a dominant of
        // the edit. Otherwise, fallback to the following code...
        val dominants = dominantsOf(section)

        (if dominants.isEmpty then section
         else
           // NASTY HACK: this is hokey, but essentially correct - if we have
           // ambiguous matches leading to multiple dominants, then they're all
           // just as good in terms of their content. So just choose any one.
           dominants.head
        ).content
      end elementsOf

      mergedSectionsResult.map {
        case FullyMerged(elements) =>
          FullyMerged(elements = elements.flatMap(elementsOf))
        case MergedWithConflicts(leftElements, rightElements) =>
          MergedWithConflicts(
            leftElements = leftElements.flatMap(elementsOf),
            rightElements = rightElements.flatMap(elementsOf)
          )
      }
end CodeMotionAnalysisExtension
