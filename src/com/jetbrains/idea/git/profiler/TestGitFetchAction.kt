package com.jetbrains.idea.git.profiler

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.MultiMap
import git4idea.GitVcs
import git4idea.commands.GitCommand
import git4idea.commands.GitSimpleHandler
import java.awt.datatransfer.StringSelection

class TestGitFetchAction : AnAction() {
  private val NOTIFICATION_GROUP = "git.commands.profiler"

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project!!
    val msg = "How many consecutive runs of `git fetch` do you want to perform?"
    val runs = Messages.showInputDialog(project, msg, "Git Fetch Duration Test",
               Messages.getQuestionIcon(), "100", INT_VALIDATOR)
    if (runs != null) {
      start(project, runs.toInt())
    }
  }

  private fun start(project: Project, runs: Int) {
    val roots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project)!!)
    val results = MultiMap<VirtualFile, Long>()
    object : Task.Backgroundable(project, "Fetching", true) {
      override fun run(pi: ProgressIndicator) {
        pi.text2 = "First cold fetch...";
        for (root in roots) {
          fetch(project, root)
        }

        for (run in 1..runs) {
          for (root in roots) {
            pi.text2 = "Fetching #$run in ${root.name}..."
            results.putValue(root, fetch(project, root))
          }
          pi.fraction = run / runs.toDouble()
        }
      }

      override fun onSuccess() {
        val text = calculate(results, runs)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Notification(NOTIFICATION_GROUP, "Git Fetch Duration Results", text, NotificationType.INFORMATION)
                .notify(project)
      }
    }.queue()
  }

  private fun calculate(results: MultiMap<VirtualFile, Long>, runs: Int): String {
    val calculated = hashMapOf<VirtualFile, Long>()
    for (root in results.keySet()) {
      val rootResults = results.get(root).sorted()
      val size = rootResults.size
      val perc10 = Math.max(2, size / 10)
      val filtered = rootResults.subList(perc10, size - perc10)
      calculated.put(root, filtered.sum() / filtered.size)
    }
    return "Fetch was called $runs times in ${calculated.size} ${StringUtil.pluralize("root", calculated.size)}<br/>" +
            "Average times without the first cold fetch and 10/90 percentiles:<br/>" +
            calculated.entries.joinToString("<br/>") { "${it.key.name}: ${it.value} ms" } +
            "\nThis text has been copied to the clipboard";
  }

  private fun fetch(project: Project, root: VirtualFile) : Long {
    val gh = GitSimpleHandler(project, root, GitCommand.FETCH)
    val start = System.currentTimeMillis()
    gh.runInCurrentThread{}
    val end = System.currentTimeMillis()
    return end - start
  }

  val INT_VALIDATOR = object : InputValidator {
    override fun checkInput(s: String?): Boolean {
      return isInt(s)
    }

    override fun canClose(s: String?): Boolean {
      return checkInput(s)
    }

    private fun isInt(s: String?): Boolean {
      try {
        s!!.toInt()
        return true;
      } catch (e: NumberFormatException) {
        return false;
      }
    }
  }
}
