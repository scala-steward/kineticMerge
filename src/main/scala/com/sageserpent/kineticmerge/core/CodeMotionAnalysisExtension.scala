package com.sageserpent.kineticmerge.core

import cats.Eq
import com.sageserpent.kineticmerge.core.merge.{Divergence, FullyMerged, MergedWithConflicts, Result}

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
    ): Either[Divergence.type, Result[Element]] =
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

      def dominantOf(section: Section[Element]): Option[Section[Element]] =
        codeMotionAnalysis
          .matchFor(section)
          .map(_.dominantElement)

      /** This is most definitely *not* [[Section.equals]] - we want to compare
        * the underlying content of the dominant sections, as the sections are
        * expected to come from *different* sides. [[Section.equals]] is
        * expected to consider sections from different sides as unequal.
        */
      def sectionEqualityViaDominants(
          lhs: Section[Element],
          rhs: Section[Element]
      ): Boolean =
        dominantOf(lhs) -> dominantOf(rhs) match
          case (Some(lhsDominantSection), Some(rhsDominantSection)) =>
            // However, it's OK to use the intrinsic equality here once we're
            // working with the dominant sections - we want to confirm that the
            // two sections belong to the same match, so they must have dominant
            // sections that are equal to each other in the intrinsic sense.
            lhsDominantSection == rhsDominantSection
          case _ => false

      val mergedSectionsResult =
        merge.of(
          base = baseSections,
          left = leftSections,
          right = rightSections
        )(equality = sectionEqualityViaDominants)

      def elementsOf(section: Section[Element]): IndexedSeq[Element] =
        dominantOf(section).getOrElse(section).content

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