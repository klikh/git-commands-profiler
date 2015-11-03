package com.jetbrains.idea.git.profiler

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.util.containers.MultiMap
import com.intellij.util.ui.JBUI
import git4idea.GitUtil
import git4idea.GitVcs
import git4idea.commands.GitCommand
import git4idea.commands.GitImpl
import git4idea.commands.GitLineHandler
import git4idea.commands.GitSimpleHandler
import git4idea.update.GitFetcher
import java.awt.BorderLayout
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TestGitFetchAction : AnAction() {
  private val NOTIFICATION_GROUP = "git.commands.profiler"

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project!!
    val props = showDialog()
    if (props != null) {
      start(project, props.runs, props.method)
    }
  }

  private data class Properties(val runs: Int, val method: Method)
  private enum class Method {
    FETCHER, SIMPLE, LINE
  }

  private fun showDialog(): Properties? {
    val msg = "How many consecutive runs of `git fetch` do you want to perform?"
    val spinner = JSpinner(SpinnerNumberModel(100, 5, 10000, 1))
    val method = ComboBox(EnumComboBoxModel(Method::class.java))

    val panel = JBUI.Panels.simplePanel();
    panel.add(JBLabel(msg), BorderLayout.NORTH)
    panel.add(spinner, BorderLayout.WEST)
    panel.add(method, BorderLayout.SOUTH)
    val ok = DialogBuilder().centerPanel(panel).title("Git Fetch Duration Test").showAndGet()

    return if (ok) {
      val runs = Integer.parseInt((spinner.editor as JSpinner.NumberEditor).textField.text)
      Properties(runs, method.model.selectedItem as Method)
    } else null;
  }

  private fun start(project: Project, runs: Int, method: Method) {
    val roots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project)!!)
    val results = MultiMap<VirtualFile, Long>()
    object : Task.Backgroundable(project, "Fetching", true) {
      override fun run(pi: ProgressIndicator) {
        pi.text2 = "First cold fetch...";
        for (root in roots) {
          fetchViaSimpleHandler(project, root)
        }

        for (run in 1..runs) {
          for (root in roots) {
            pi.text2 = "Fetching #$run in ${root.name}..."
            val fetch = when (method) {
              Method.SIMPLE -> fetchViaSimpleHandler(project, root)
              Method.LINE -> fetchViaGitImpl(project, root)
              Method.FETCHER -> fetchViaFetcher(project, root)
            }
            results.putValue(root, fetch)
          }
          pi.fraction = run / runs.toDouble()
        }
      }

      override fun onSuccess() {
        val text = calculate(results, runs, method)
        Notification(NOTIFICATION_GROUP, "Git Fetch Duration Results", text, NotificationType.INFORMATION)
                .notify(project)
      }
    }.queue()
  }

  private fun calculate(results: MultiMap<VirtualFile, Long>, runs: Int, method: Method): String {
    val calculated = hashMapOf<VirtualFile, Long>()
    for (root in results.keySet()) {
      val rootResults = results.get(root).sorted()
      val size = rootResults.size
      val perc10 = Math.max(2, size / 10)
      val filtered = rootResults.subList(perc10, size - perc10)
      calculated.put(root, filtered.sum() / filtered.size)
    }
    return "Fetch was called $runs times in ${calculated.size} ${StringUtil.pluralize("root", calculated.size)}<br/>" +
            "Method used: ${method.name.toLowerCase().capitalize()}<br/>" +
            "Average times without the first cold fetch and 10/90 percentiles:<br/>" +
            calculated.entries.joinToString("<br/>") { "${it.key.name}: ${it.value} ms" }
  }

  private fun fetchViaFetcher(project: Project, root: VirtualFile): Long {
    return measure {
      val manager = GitUtil.getRepositoryManager(project)
      val roots = GitUtil.getRepositoriesFromRoots(manager, listOf(root))
      val indicator = ProgressManager.getInstance().progressIndicator
      GitFetcher(project, indicator, false).fetchRootsAndNotify(roots, null, false)
    }
  }

  private fun fetchViaSimpleHandler(project: Project, root: VirtualFile): Long {
    return measure {
      val gh = GitSimpleHandler(project, root, GitCommand.FETCH)
      gh.runInCurrentThread{}
    }
  }

  private fun fetchViaGitImpl(project: Project, root: VirtualFile): Long {
    return measure {
      GitImpl().runCommand(object : Computable<GitLineHandler> {
        override fun compute(): GitLineHandler {
          val h = GitLineHandler(project, root, GitCommand.FETCH)
          h.setSilent(false)
          h.isStdoutSuppressed = false
          h.addProgressParameter()
          h.addParameters("--prune")
          return h
        }
      })
    }
  }

  private fun measure(f: () -> Unit) : Long{
    val start = System.currentTimeMillis()
    f()
    val end = System.currentTimeMillis()
    return end - start
  }
}
