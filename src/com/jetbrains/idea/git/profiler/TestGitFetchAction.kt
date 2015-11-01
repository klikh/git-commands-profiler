package com.jetbrains.idea.git.profiler

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.MultiMap
import git4idea.GitVcs
import git4idea.commands.GitCommand
import git4idea.commands.GitSimpleHandler
import java.awt.datatransfer.StringSelection

class TestGitFetchAction : AnAction() {
  private val RUNS = 10

  override fun actionPerformed(event: AnActionEvent) {
    val msg = "Start $RUNS `git fetch` consequent processes to measure the average duration of the operation?"
    val an = Messages.showOkCancelDialog(msg, "Git Fetch Duration Test", "Start", "Cancel", Messages.getQuestionIcon())
    if (an == Messages.OK) start(event.project!!)
  }

  private fun start(project: Project) {
    val roots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project)!!)
    val results = MultiMap<VirtualFile, Long>()
    object : Task.Modal(project, "Fetching", true){
      override fun run(pi: ProgressIndicator) {
        // cold fetch
        for (root in roots) {
          fetch(project, root)
        }

        for (run in 1..RUNS) {
          pi.fraction = run / RUNS.toDouble()
          for (root in roots) {
            results.putValue(root, fetch(project, root))
          }
        }
      }
    }.queue()

    val text = calculate(results)
    CopyPasteManager.getInstance().setContents(StringSelection(text))
    Messages.showInfoMessage(text, "Git Fetch Results")
  }

  private fun calculate(results: MultiMap<VirtualFile, Long>): String {
    val calculated = hashMapOf<VirtualFile, Long>()
    for (root in results.keySet()) {
      val rootResults = results.get(root).sorted()
      val size = rootResults.size
      val perc10 = Math.max(2, size / 10)
      val filtered = rootResults.subList(perc10, size - perc10)
      calculated.put(root, filtered.sum() / filtered.size)
    }

    return "Fetch was called $RUNS times in ${calculated.size()} roots\n" +
            "Average times without the first cold fetch and 10/90 percentile:\n" +
            calculated.entries.joinToString("\n") { "${it.key.name}: ${it.value} ms" } +
            "\nThis text has been copied to the clipboard";
  }

  private fun fetch(project: Project, root: VirtualFile) : Long {
    val gh = GitSimpleHandler(project, root, GitCommand.FETCH)
    val start = System.currentTimeMillis()
    gh.runInCurrentThread{}
    val end = System.currentTimeMillis()
    return end - start
  }
}
