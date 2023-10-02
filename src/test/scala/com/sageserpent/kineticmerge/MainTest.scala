package com.sageserpent.kineticmerge

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.sageserpent.americium.Trials
import com.sageserpent.americium.Trials.api as trialsApi
import com.sageserpent.americium.junit5.{DynamicTests, *}
import com.sageserpent.kineticmerge.Main.Tags
import com.sageserpent.kineticmerge.MainTest.*
import com.sageserpent.kineticmerge.core.ExpectyFlavouredAssert.assert
import com.softwaremill.tagging.*
import org.junit.jupiter.api.{BeforeEach, DynamicTest, Test, TestFactory}

import java.io.{ByteArrayInputStream, File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator
import scala.language.postfixOps
import scala.sys.process.Process

object MainTest:
  private type ImperativeResource[Payload] = Resource[IO, Payload]

  private val masterBranch = "master"

  private val arthur = "arthur.txt"

  private val sandra = "sandra.txt"

  private val tyson = "tyson.txt"

  private val optionalSubdirectories: Trials[Option[Path]] =
    trialsApi.only("runMergeInHere").map(Path.of(_)).options

  private def introducingArthur(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(path.resolve(arthur), "Hello, my old mucker!\n")
    println(s"git add $arthur" !!)
    println(s"git commit -m 'Introducing Arthur.'" !!)
  end introducingArthur

  private def arthurContinues(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(
      path.resolve(arthur),
      "Pleased to see you, old boy.\n",
      StandardOpenOption.APPEND
    )
    println(s"git commit -am 'Arthur continues...'" !!)
  end arthurContinues

  private def arthurElaborates(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(
      path.resolve(arthur),
      "Pleased to see you, old chap.\n",
      StandardOpenOption.APPEND
    )
    println(s"git commit -am 'Arthur elaborates.'" !!)
  end arthurElaborates

  private def arthurCorrectHimself(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(
      path.resolve(arthur),
      "Hello, all and sundry!\n",
      StandardOpenOption.TRUNCATE_EXISTING
    )
    println(s"git commit -am 'Arthur corrects himself.'" !!)
  end arthurCorrectHimself

  private def exeuntArthur()(using ProcessBuilderFromCommandString): Unit =
    println(s"git rm $arthur" !!)
    println(s"git commit -m 'Exeunt Arthur.'" !!)
  end exeuntArthur

  private def arthurExcusesHimself()(using
      ProcessBuilderFromCommandString
  ): Unit =
    println(s"git rm $arthur" !!)
    println(s"git commit -m 'Arthur excuses himself.'" !!)
  end arthurExcusesHimself

  private def enterTysonStageLeft(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(path.resolve(tyson), "Alright marra!\n")
    println(s"git add $tyson" !!)
    println(s"git commit -m 'Tyson responds.'" !!)
  end enterTysonStageLeft

  private def evilTysonMakesDramaticEntranceExulting(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(path.resolve(tyson), "Ha, ha, ha, ha, hah!\n")
    println(s"git add $tyson" !!)
    println(s"git commit -m 'Evil Tyson exults.'" !!)
  end evilTysonMakesDramaticEntranceExulting

  private def sandraHeadsOffHome()(using
      ProcessBuilderFromCommandString
  ): Unit =
    println(s"git rm $sandra" !!)
    println(s"git commit -m 'Sandra heads off home.'" !!)
  end sandraHeadsOffHome

  private def sandraStopsByBriefly(path: Path)(using
      ProcessBuilderFromCommandString
  ): Unit =
    Files.writeString(
      path.resolve(sandra),
      "Hiya - just gan yam now...\n"
    )
    println(s"git add $sandra" !!)
    println(s"git commit -m 'Sandra stops by briefly...'" !!)
  end sandraStopsByBriefly

  private def noUpdatesInIndexForArthur(status: String): Unit =
    assert(!status.contains(arthur))

  private def arthurIsMarkedWithConflictingUpdatesInTheIndex(
      status: String
  ): Unit =
    assert(
      s"UU\\s+$arthur".r.findFirstIn(status).isDefined
    )

  private def arthurIsMarkedWithConflictingDeletionAndUpdateInTheIndex(
      flipBranches: Boolean,
      status: String
  ): Unit =
    assert(
      s"${if flipBranches then "DU" else "UD"}\\s+$arthur".r
        .findFirstIn(status)
        .isDefined
    )

  private def noUpdatesInIndexForTyson(status: String): Unit =
    assert(!status.contains(tyson))

  private def tysonIsMarkedAsAddedInTheIndex(status: String): Unit =
    assert(s"A\\s+$tyson.*".r.findFirstIn(status).isDefined)

  private def tysonIsMarkedWithConflictingAddiitonsInTheIndex(
      status: String
  ): Unit =
    assert(s"AA\\s+$tyson".r.findFirstIn(status).isDefined)

  private def noUpdatesInIndexForSandra(status: String): Unit =
    assert(!status.contains(sandra))

  private def sandraIsMarkedAsDeletedInTheIndex(status: String): Unit =
    assert(s"D\\s+$sandra".r.findFirstIn(status).isDefined)

  private def verifyTrivialMergeMovesToTheMostAdvancedCommitLeavesWithACleanIndex(
      commitOfAdvancedBranch: String,
      ourBranch: String,
      exitCode: Int @@ Main.Tags.ExitCode
  )(using ProcessBuilderFromCommandString): Unit =
    assert(exitCode == 0)

    val branchName = ("git branch --show-current" !!).strip()

    assert(branchName == ourBranch)

    val postMergeCommitOfAdvancedBranch =
      (s"git log -1 --format=tformat:%H" !!).strip

    assert(postMergeCommitOfAdvancedBranch == commitOfAdvancedBranch)

    val status = (s"git status --short" !!).strip

    assert(status.isEmpty)
  end verifyTrivialMergeMovesToTheMostAdvancedCommitLeavesWithACleanIndex

  private def verifyMergeMakesANewCommitWithACleanIndex(
      commitOfConcurrentlyModifiedFileBranch: String,
      commitOfMasterBranch: String,
      ourBranch: String,
      exitCode: Int @@ Main.Tags.ExitCode
  )(using ProcessBuilderFromCommandString): Unit =
    assert(exitCode == 0)

    val branchName = ("git branch --show-current" !!).strip()

    assert(branchName == ourBranch)

    val postMergeCommit =
      (s"git log -1 --format=tformat:%H" !!).strip

    assert(postMergeCommit != commitOfMasterBranch)
    assert(postMergeCommit != commitOfConcurrentlyModifiedFileBranch)

    val commitOfMasterBranchIsAncestor =
      (s"git merge-base --is-ancestor $commitOfMasterBranch $postMergeCommit" !) == 0

    assert(commitOfMasterBranchIsAncestor)

    val commitOfNewFileBranchIsAncestor =
      (s"git merge-base --is-ancestor $commitOfConcurrentlyModifiedFileBranch $postMergeCommit" !) == 0

    assert(commitOfNewFileBranchIsAncestor)

    val status = (s"git status --short" !!).strip

    assert(status.isEmpty)
  end verifyMergeMakesANewCommitWithACleanIndex

  private def verifyAConflictedMergeDoesNotMakeACommitAndLeavesADirtyIndex(
      flipBranches: Boolean,
      commitOfDeletedFileBranch: String,
      commitOfMasterBranch: String,
      ourBranch: String,
      exitCode: Int @@ Main.Tags.ExitCode
  )(using ProcessBuilderFromCommandString): Unit =
    assert(exitCode == 1)

    val branchName = ("git branch --show-current" !!).strip()

    assert(branchName == ourBranch)

    val postMergeCommit =
      (s"git log -1 --format=tformat:%H" !!).strip

    assert(
      postMergeCommit == (if flipBranches then commitOfDeletedFileBranch
                          else commitOfMasterBranch)
    )
  end verifyAConflictedMergeDoesNotMakeACommitAndLeavesADirtyIndex

  private def gitRepository(): ImperativeResource[Path] =
    for
      temporaryDirectory <- Resource.make(IO {
        Files.createTempDirectory("toyGitRepository")
      })(temporaryDirectory =>
        IO {
          Files
            .walkFileTree(
              temporaryDirectory,
              new FileVisitor[Path]:
                override def preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult = FileVisitResult.CONTINUE

                override def visitFile(
                    path: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult =
                  Files.delete(path)
                  FileVisitResult.CONTINUE
                end visitFile

                override def visitFileFailed(
                    file: Path,
                    exc: IOException
                ): FileVisitResult = FileVisitResult.CONTINUE

                override def postVisitDirectory(
                    path: Path,
                    exc: IOException
                ): FileVisitResult =
                  Files.delete(path)
                  FileVisitResult.CONTINUE
                end postVisitDirectory
            )
        }
      )

      given ProcessBuilderFromCommandString =
        processBuilderFromCommandStringUsing(
          temporaryDirectory
        )

      _ <- Resource.eval(IO { "git init" !! })
      _ <- Resource.eval(IO { s"git checkout -b $masterBranch" !! })
    yield temporaryDirectory
    end for
  end gitRepository

end MainTest

class MainTest:
  @TestFactory
  def trivialMerge(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              val advancedBranch = "advancedBranch"

              println(s"git checkout -b $advancedBranch" !!)

              arthurContinues(path)

              val commitOfAdvancedBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then println(s"git checkout $masterBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then masterBranch -> advancedBranch
                else advancedBranch               -> masterBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyTrivialMergeMovesToTheMostAdvancedCommitLeavesWithACleanIndex(
                commitOfAdvancedBranch,
                ourBranch,
                exitCode
              )
            }
          )
          .unsafeRunSync()
      }
  end trivialMerge

  @TestFactory
  def cleanMergeBringingInANewFile(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              val newFileBranch = "newFileBranch"

              println(s"git checkout -b $newFileBranch" !!)

              enterTysonStageLeft(path)

              val commitOfNewFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              arthurContinues(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then println(s"git checkout $newFileBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then newFileBranch -> masterBranch
                else masterBranch                  -> newFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyMergeMakesANewCommitWithACleanIndex(
                commitOfNewFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )
            }
          )
          .unsafeRunSync()
      }
  end cleanMergeBringingInANewFile

  @TestFactory
  def cleanMergeDeletingAFile(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              val deletedFileBranch = "deletedFileBranch"

              println(s"git checkout -b $deletedFileBranch" !!)

              exeuntArthur()

              val commitOfDeletedFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              enterTysonStageLeft(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then
                println(s"git checkout $deletedFileBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then deletedFileBranch -> masterBranch
                else masterBranch                      -> deletedFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyMergeMakesANewCommitWithACleanIndex(
                commitOfDeletedFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )
            }
          )
          .unsafeRunSync()
      }
  end cleanMergeDeletingAFile

  @TestFactory
  def conflictingAdditionOfTheSameFile(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              sandraStopsByBriefly(path)

              val evilTwinBranch = "evilTwin"

              println(s"git checkout -b $evilTwinBranch" !!)

              evilTysonMakesDramaticEntranceExulting(path)

              val commitOfEvilTwinBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              sandraHeadsOffHome()

              enterTysonStageLeft(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then println(s"git checkout $evilTwinBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then evilTwinBranch -> masterBranch
                else masterBranch                   -> evilTwinBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyAConflictedMergeDoesNotMakeACommitAndLeavesADirtyIndex(
                flipBranches,
                commitOfEvilTwinBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )

              val status = (s"git status --short" !!).strip

              noUpdatesInIndexForArthur(status)

              tysonIsMarkedWithConflictingAddiitonsInTheIndex(status)

              if flipBranches then sandraIsMarkedAsDeletedInTheIndex(status)
              else noUpdatesInIndexForSandra(status)
              end if
            }
          )
          .unsafeRunSync()
      }
  end conflictingAdditionOfTheSameFile

  @TestFactory
  def conflictingModificationAndDeletionOfTheSameFile(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              sandraStopsByBriefly(path)

              val deletedFileBranch = "deletedFileBranch"

              println(s"git checkout -b $deletedFileBranch" !!)

              enterTysonStageLeft(path)

              exeuntArthur()

              val commitOfDeletedFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              sandraHeadsOffHome()

              arthurContinues(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then
                println(s"git checkout $deletedFileBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then deletedFileBranch -> masterBranch
                else masterBranch                      -> deletedFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyAConflictedMergeDoesNotMakeACommitAndLeavesADirtyIndex(
                flipBranches,
                commitOfDeletedFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )

              val status = (s"git status --short" !!).strip

              arthurIsMarkedWithConflictingDeletionAndUpdateInTheIndex(
                flipBranches,
                status
              )

              if flipBranches then
                sandraIsMarkedAsDeletedInTheIndex(status)
                noUpdatesInIndexForTyson(status)
              else
                noUpdatesInIndexForSandra(status)
                tysonIsMarkedAsAddedInTheIndex(status)
              end if
            }
          )
          .unsafeRunSync()
      }
  end conflictingModificationAndDeletionOfTheSameFile

  @TestFactory
  def conflictingModificationOfTheSameFile(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              sandraStopsByBriefly(path)

              val concurrentlyModifiedFileBranch =
                "concurrentlyModifiedFileBranch"

              println(s"git checkout -b $concurrentlyModifiedFileBranch" !!)

              enterTysonStageLeft(path)

              arthurElaborates(path)

              val commitOfConcurrentlyModifiedFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              sandraHeadsOffHome()

              arthurContinues(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then
                println(s"git checkout $concurrentlyModifiedFileBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then
                  concurrentlyModifiedFileBranch -> masterBranch
                else masterBranch -> concurrentlyModifiedFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyAConflictedMergeDoesNotMakeACommitAndLeavesADirtyIndex(
                flipBranches,
                commitOfConcurrentlyModifiedFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )

              val status = (s"git status --short" !!).strip

              arthurIsMarkedWithConflictingUpdatesInTheIndex(status)

              if flipBranches then
                sandraIsMarkedAsDeletedInTheIndex(status)
                noUpdatesInIndexForTyson(status)
              else
                noUpdatesInIndexForSandra(status)
                tysonIsMarkedAsAddedInTheIndex(status)
              end if
            }
          )
          .unsafeRunSync()
      }
  end conflictingModificationOfTheSameFile

  @TestFactory
  def cleanMergeOfAFileDeletedInBothBranches(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              sandraStopsByBriefly(path)

              val concurrentlyDeletedFileBranch =
                "concurrentlyDeletedFileBranch"

              println(s"git checkout -b $concurrentlyDeletedFileBranch" !!)

              enterTysonStageLeft(path)

              exeuntArthur()

              val commitOfConcurrentlyDeletedFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              sandraHeadsOffHome()

              arthurContinues(path)

              arthurExcusesHimself()

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then
                println(
                  s"git checkout $concurrentlyDeletedFileBranch" !!
                )
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then
                  concurrentlyDeletedFileBranch -> masterBranch
                else masterBranch               -> concurrentlyDeletedFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyMergeMakesANewCommitWithACleanIndex(
                commitOfConcurrentlyDeletedFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )
            }
          )
          .unsafeRunSync()
      }
  end cleanMergeOfAFileDeletedInBothBranches

  @TestFactory
  def cleanMergeOfAFileModifiedInBothBranches(): DynamicTests =
    (optionalSubdirectories and trialsApi.booleans)
      .withLimit(4)
      .dynamicTests { case (optionalSubdirectory, flipBranches) =>
        gitRepository()
          .use(path =>
            IO {
              optionalSubdirectory.foreach(subdirectory =>
                Files.createDirectory(path.resolve(subdirectory))
              )

              given ProcessBuilderFromCommandString =
                processBuilderFromCommandStringUsing(path)

              introducingArthur(path)

              sandraStopsByBriefly(path)

              val concurrentlyModifiedFileBranch =
                "concurrentlyModifiedFileBranch"

              println(s"git checkout -b $concurrentlyModifiedFileBranch" !!)

              enterTysonStageLeft(path)

              arthurCorrectHimself(path)

              val commitOfConcurrentlyModifiedFileBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              println(s"git checkout $masterBranch" !!)

              sandraHeadsOffHome()

              arthurContinues(path)

              val commitOfMasterBranch =
                (s"git log -1 --format=tformat:%H" !!).strip

              if flipBranches then
                println(s"git checkout $concurrentlyModifiedFileBranch" !!)
              end if

              val (ourBranch, theirBranch) =
                if flipBranches then
                  concurrentlyModifiedFileBranch -> masterBranch
                else masterBranch -> concurrentlyModifiedFileBranch

              val exitCode = Main.mergeTheirBranch(
                theirBranch.taggedWith[Tags.CommitOrBranchName]
              )(workingDirectory =
                optionalSubdirectory.fold(ifEmpty = path)(path.resolve)
              )

              verifyMergeMakesANewCommitWithACleanIndex(
                commitOfConcurrentlyModifiedFileBranch,
                commitOfMasterBranch,
                ourBranch,
                exitCode
              )
            }
          )
          .unsafeRunSync()
      }
  end cleanMergeOfAFileModifiedInBothBranches
end MainTest