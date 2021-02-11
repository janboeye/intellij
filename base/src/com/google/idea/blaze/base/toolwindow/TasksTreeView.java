/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.toolwindow;

import com.google.idea.common.ui.templates.AbstractView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.AbstractTreeModel;
import java.awt.Component;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;

/** The view that represents the tree of the hierarchy of tasks. */
final class TasksTreeView extends AbstractView<Tree> {
  private final TasksTreeModel model;

  private final TreeSelectionListener treeSelectionListener = this::onTaskSelected;

  TasksTreeView(TasksTreeModel model) {
    this.model = model;
  }

  @Override
  protected Tree createComponent() {
    Tree tree = new Tree(new TreeModel());
    tree.setRootVisible(false);
    tree.setCellRenderer(new TreeCellRenderer());
    return tree;
  }

  @Override
  protected void bind() {
    getComponent().addTreeSelectionListener(treeSelectionListener);
  }

  @Override
  protected void unbind() {
    getComponent().removeTreeSelectionListener(treeSelectionListener);
  }

  private void onTaskSelected(TreeSelectionEvent event) {
    TreePath selectionPath = event.getNewLeadSelectionPath();
    Object selection = selectionPath == null ? null : selectionPath.getLastPathComponent();
    model.selectedTaskProperty().setValue(selection instanceof Task ? (Task) selection : null);
  }

  /** Swing's TreeModel implementation that reflects the hierarchy of the tasks. */
  private class TreeModel extends AbstractTreeModel {
    /** The invisible root of the tree. */
    final Task root = new Task("Root", Task.Type.OTHER, null, model.getTopLevelTasks());

    @Override
    public Object getRoot() {
      return root;
    }

    @Override
    public Object getChild(Object parent, int index) {
      checkNodeType(parent);
      return ((Task) parent).getChildren().get(index);
    }

    @Override
    public int getChildCount(Object parent) {
      checkNodeType(parent);
      return ((Task) parent).getChildren().size();
    }

    @Override
    public boolean isLeaf(Object node) {
      checkNodeType(node);
      return ((Task) node).getChildren().isEmpty();
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
      checkNodeType(parent);
      checkNodeType(child);
      return ((Task) parent).getChildren().indexOf(child);
    }

    private void checkNodeType(Object node) {
      if (!(node instanceof Task)) {
        throw new IllegalStateException("Task trees can only contain Task instances");
      }
    }
  }

  /** Swing's TreeCellRenderer that defines the representation of the tree node. */
  private static final class TreeCellRenderer extends DefaultTreeCellRenderer {
    static final Icon NODE_ICON_RUNNING = new AnimatedIcon.Default();
    private static final Icon NODE_ICON_OK = AllIcons.RunConfigurations.TestPassed;
    private static final Icon NODE_ICON_ERROR = AllIcons.RunConfigurations.TestError;

    @Override
    public Component getTreeCellRendererComponent(
        JTree tree,
        Object value,
        boolean selected,
        boolean expanded,
        boolean leaf,
        int row,
        boolean hasFocus) {
      if (!(value instanceof Task)) {
        throw new IllegalStateException("Task trees can only contain Task instances");
      }

      super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

      Task task = (Task) value;
      if (task.isFinished()) {
        setIcon(task.getHasErrors() ? NODE_ICON_ERROR : NODE_ICON_OK);
      } else {
        setIcon(NODE_ICON_RUNNING);
      }
      setText(makeLabelText(task));

      return this;
    }

    private static String makeLabelText(Task task) {
      return "<html>"
          + task.getName()
          + ' '
          + "<font color=gray>"
          + startTimeLabel(task)
          + ' '
          + durationLabel(task)
          + "</font></html>";
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String startTimeLabel(Task task) {
      return task.getStartTime()
          .map(s -> TIME_FORMATTER.format(LocalDateTime.ofInstant(s, ZoneId.systemDefault())))
          .orElse("");
    }

    private static String durationLabel(Task task) {
      return task.getEndTime()
          .flatMap(s -> task.getEndTime().map(e -> Duration.between(s, e)))
          .map(d -> '[' + StringUtil.formatDuration(d.toMillis()) + ']')
          .orElse("");
    }
  }
}
