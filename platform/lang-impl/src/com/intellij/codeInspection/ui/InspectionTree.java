// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.ui;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeComparator;
import com.intellij.psi.PsiElement;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

import static com.intellij.codeInspection.CommonProblemDescriptor.DESCRIPTOR_COMPARATOR;

public class InspectionTree extends Tree {
  private static final Logger LOG = Logger.getInstance(InspectionTree.class);

  @NotNull private final GlobalInspectionContextImpl myContext;
  @NotNull private final ConcurrentMap<HighlightDisplayLevel, InspectionSeverityGroupNode> mySeverityGroupNodes = ContainerUtil.newConcurrentMap();
  @NotNull private final ConcurrentMap<HighlightDisplayLevel, ConcurrentMap<String[], InspectionGroupNode>> myGroups = ContainerUtil.newConcurrentMap();

  @NotNull private InspectionTreeState myState = new InspectionTreeState();
  private boolean myQueueUpdate;

  public InspectionTree(@NotNull GlobalInspectionContextImpl context,
                        @NotNull InspectionResultsView view) {
    Project project = context.getProject();
    setModel(new DefaultTreeModel(new InspectionRootNode(project, new InspectionTreeUpdater(view))));
    myContext = context;

    setCellRenderer(new InspectionTreeCellRenderer(view));
    setRootVisible(false);
    setShowsRootHandles(true);
    UIUtil.setLineStyleAngled(this);
    addTreeWillExpandListener(new ExpandListener());

    myState.getExpandedUserObjects().add(project);

    TreeUtil.installActions(this);
    new TreeSpeedSearch(this, o -> InspectionsConfigTreeComparator.getDisplayTextToSort(o.getLastPathComponent().toString()));

    addTreeSelectionListener(e -> {
      TreePath newSelection = e.getNewLeadSelectionPath();
      if (newSelection != null && !isUnderQueueUpdate()) {
        myState.setSelectionPath(newSelection);
      }
    });
  }

  public void setQueueUpdate(boolean queueUpdate) {
    myQueueUpdate = queueUpdate;
  }

  public boolean isUnderQueueUpdate() {
    return myQueueUpdate;
  }

  public void removeAllNodes() {
    mySeverityGroupNodes.clear();
    myGroups.clear();
    getRoot().removeAllChildren();
    ApplicationManager.getApplication().invokeLater(() -> nodeStructureChanged(getRoot()));
  }

  public InspectionTreeNode getRoot() {
    return (InspectionTreeNode)getModel().getRoot();
  }

  @Nullable
  public String[] getSelectedGroupPath() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return null;
    final TreePath commonPath = TreeUtil.findCommonPath(paths);
    for (Object n : commonPath.getPath()) {
      if (n instanceof InspectionGroupNode) {
        return getGroupPath((InspectionGroupNode)n);
      }
    }
    return null;
  }

  @Nullable
  public InspectionToolWrapper getSelectedToolWrapper(boolean allowDummy) {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return null;
    InspectionToolWrapper toolWrapper = null;
    for (TreePath path : paths) {
      Object[] nodes = path.getPath();
      for (int j = nodes.length - 1; j >= 0; j--) {
        Object node = nodes[j];
        if (node instanceof InspectionGroupNode) {
          return null;
        }
        if (node instanceof InspectionNode) {
          InspectionToolWrapper wrapper = ((InspectionNode)node).getToolWrapper();
          if (!allowDummy && getContext().getPresentation(wrapper).isDummy()) {
            continue;
          }
          if (toolWrapper == null) {
            toolWrapper = wrapper;
          }
          else if (toolWrapper != wrapper) {
            return null;
          }
          break;
        }
      }
    }

    return toolWrapper;
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    TreePath path = getPathForLocation(e.getX(), e.getY());
    if (path == null) return null;
    Object lastComponent = path.getLastPathComponent();
    if (!(lastComponent instanceof ProblemDescriptionNode)) return null;
    return ((ProblemDescriptionNode)lastComponent).getToolTipText();
  }

  @Nullable
  public RefEntity getCommonSelectedElement() {
    final Object node = getCommonSelectedNode();
    return node instanceof RefElementNode ? ((RefElementNode)node).getElement() : null;
  }

  @Nullable
  private Object getCommonSelectedNode() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return null;
    final Object[][] resolvedPaths = new Object[paths.length][];
    for (int i = 0; i < paths.length; i++) {
      TreePath path = paths[i];
      resolvedPaths[i] = path.getPath();
    }

    Object currentCommonNode = null;
    for (int i = 0; i < resolvedPaths[0].length; i++) {
      final Object currentNode = resolvedPaths[0][i];
      for (int j = 1; j < resolvedPaths.length; j++) {
        final Object o = resolvedPaths[j][i];
        if (!o.equals(currentNode)) {
          return currentCommonNode;
        }
      }
      currentCommonNode = currentNode;
    }
    return currentCommonNode;
  }

  @NotNull
  public RefEntity[] getSelectedElements() {
    TreePath[] selectionPaths = getSelectionPaths();
    if (selectionPaths != null) {
      InspectionToolWrapper toolWrapper = getSelectedToolWrapper(true);
      if (toolWrapper == null) return RefEntity.EMPTY_ELEMENTS_ARRAY;

      Set<RefEntity> result = new LinkedHashSet<>();
      for (TreePath selectionPath : selectionPaths) {
        final InspectionTreeNode node = (InspectionTreeNode)selectionPath.getLastPathComponent();
        addElementsInNode(node, result);
      }
      return ArrayUtil.reverseArray(result.toArray(new RefEntity[result.size()]));
    }
    return RefEntity.EMPTY_ELEMENTS_ARRAY;
  }

  private static void addElementsInNode(InspectionTreeNode node, Set<RefEntity> out) {
    if (!node.isValid()) return;
    if (node instanceof RefElementNode) {
      final RefEntity element = ((RefElementNode)node).getElement();
      out.add(element);
    }
    if (node instanceof ProblemDescriptionNode) {
      final RefEntity element = ((ProblemDescriptionNode)node).getElement();
      out.add(element);
    }
    final Enumeration children = node.children();
    while (children.hasMoreElements()) {
      InspectionTreeNode child = (InspectionTreeNode)children.nextElement();
      addElementsInNode(child, out);
    }
  }

  @NotNull
  public CommonProblemDescriptor[] getAllValidSelectedDescriptors() {
    return getSelectedDescriptors(false, null, true, false);
  }

  @NotNull
  public CommonProblemDescriptor[] getSelectedDescriptors() {
    return getSelectedDescriptors(false, null, false, false);
  }

  @NotNull
  public CommonProblemDescriptor[] getSelectedDescriptors(boolean sortedByPosition,
                                                          @Nullable Set<VirtualFile> readOnlyFilesSink,
                                                          boolean allowResolved,
                                                          boolean allowSuppressed) {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return CommonProblemDescriptor.EMPTY_ARRAY;
    final TreePath[] selectionPaths = TreeUtil.selectMaximals(paths);
    final List<CommonProblemDescriptor> descriptors = new ArrayList<>();

    MultiMap<Object, ProblemDescriptionNode> parentToChildNode = new MultiMap<>();
    final List<InspectionTreeNode> nonDescriptorNodes = new SmartList<>();
    for (TreePath path : selectionPaths) {
      final Object[] pathAsArray = path.getPath();
      final int length = pathAsArray.length;
      final Object node = pathAsArray[length - 1];
      if (node instanceof ProblemDescriptionNode) {
        if (isNodeValidAndIncluded((ProblemDescriptionNode)node, allowResolved, allowSuppressed)) {
          if (length >= 2) {
            parentToChildNode.putValue(pathAsArray[length - 2], (ProblemDescriptionNode)node);
          } else {
            parentToChildNode.putValue(node, (ProblemDescriptionNode)node);
          }
        }
      } else {
        nonDescriptorNodes.add((InspectionTreeNode)node);
      }
    }

    for (InspectionTreeNode node : nonDescriptorNodes) {
      processChildDescriptorsDeep(node, descriptors, sortedByPosition, allowResolved, allowSuppressed, readOnlyFilesSink);
    }

    for (Map.Entry<Object, Collection<ProblemDescriptionNode>> entry : parentToChildNode.entrySet()) {
      final Collection<ProblemDescriptionNode> siblings = entry.getValue();
      if (siblings.size() == 1) {
        final ProblemDescriptionNode descriptorNode = ContainerUtil.getFirstItem(siblings);
        LOG.assertTrue(descriptorNode != null);
        CommonProblemDescriptor descriptor = descriptorNode.getDescriptor();
        if (descriptor != null) {
          descriptors.add(descriptor);
          if (readOnlyFilesSink != null) {
            collectReadOnlyFiles(descriptor, readOnlyFilesSink);
          }
        }
      } else {
        List<CommonProblemDescriptor> currentDescriptors = new ArrayList<>();
        for (ProblemDescriptionNode sibling : siblings) {
          final CommonProblemDescriptor descriptor = sibling.getDescriptor();
          if (descriptor != null) {
            if (readOnlyFilesSink != null) {
              collectReadOnlyFiles(descriptor, readOnlyFilesSink);
            }
            currentDescriptors.add(descriptor);
          }
        }
        if (sortedByPosition) {
          Collections.sort(currentDescriptors, DESCRIPTOR_COMPARATOR);
        }
        descriptors.addAll(currentDescriptors);
      }
    }

    return descriptors.toArray(new CommonProblemDescriptor[descriptors.size()]);
  }

  @NotNull
  InspectionTreeNode getToolParentNode(@NotNull InspectionToolWrapper toolWrapper,
                                       HighlightDisplayLevel errorLevel,
                                       boolean groupedBySeverity,
                                       boolean isSingleInspectionRun) {
    //synchronize
    if (!groupedBySeverity && isSingleInspectionRun) {
      return getRoot();
    }
    String[] groupPath = toolWrapper.getGroupPath();
    if (groupPath.length == 0) {
      LOG.error("groupPath is empty for tool: " + toolWrapper.getShortName() + ", class: " + toolWrapper.getTool().getClass());
      return getRelativeRootNode(groupedBySeverity, errorLevel);
    }
    ConcurrentMap<String[], InspectionGroupNode> map = myGroups.get(errorLevel);
    if (map == null) {
      map = ConcurrencyUtil.cacheOrGet(myGroups, errorLevel, ConcurrentCollectionFactory.createMap(new TObjectHashingStrategy<String[]>() {
        @Override
        public int computeHashCode(String[] object) {
          return Arrays.hashCode(object);
        }

        @Override
        public boolean equals(String[] o1, String[] o2) {
          return Arrays.equals(o1, o2);
        }
      }));
    }
    InspectionGroupNode group;
    if (groupedBySeverity) {
      group = map.get(groupPath);
    }
    else {
      group = null;
      for (Map<String[], InspectionGroupNode> groupMap : myGroups.values()) {
        if ((group = groupMap.get(groupPath)) != null) break;
      }
    }
    if (group == null) {
      if (isSingleInspectionRun) {
        return getRelativeRootNode(true, errorLevel);
      }
      group = map.computeIfAbsent(groupPath, this::insertGroupNode);
    }
    return group;
  }

  @NotNull
  private InspectionTreeNode getRelativeRootNode(boolean isGroupedBySeverity, HighlightDisplayLevel level) {
    if (isGroupedBySeverity) {
      InspectionSeverityGroupNode severityGroupNode = mySeverityGroupNodes.get(level);
      if (severityGroupNode == null) {
        InspectionSeverityGroupNode newNode = new InspectionSeverityGroupNode(myContext.getProject(), level);
        severityGroupNode = ConcurrencyUtil.cacheOrGet(mySeverityGroupNodes, level, newNode);
        if (severityGroupNode == newNode) {
          InspectionTreeNode root = getRoot();
          root.insertByOrder(severityGroupNode, false);
        }
      }
      return severityGroupNode;
    }
    return getRoot();
  }

  public boolean areDescriptorNodesSelected() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return false;
    for (TreePath path : paths) {
      if (!(path.getLastPathComponent() instanceof ProblemDescriptionNode)) {
        return false;
      }
    }
    return true;
  }

  public int getSelectedProblemCount(boolean allowSuppressed) {
    int count = 0;
    for (TreePath path : TreeUtil.selectMaximals(getSelectionPaths())) {
      count += ((InspectionTreeNode)path.getLastPathComponent()).getProblemCount(allowSuppressed);
    }
    return count;
  }

  private static void processChildDescriptorsDeep(InspectionTreeNode node,
                                                  List<CommonProblemDescriptor> descriptors,
                                                  boolean sortedByPosition,
                                                  boolean allowResolved,
                                                  boolean allowSuppressed,
                                                  @Nullable Set<VirtualFile> readOnlyFilesSink) {
    List<CommonProblemDescriptor> descriptorChildren = null;
    for (int i = 0; i < node.getChildCount(); i++) {
      final TreeNode child = node.getChildAt(i);
      if (child instanceof ProblemDescriptionNode) {
        if (isNodeValidAndIncluded((ProblemDescriptionNode)child, allowResolved, allowSuppressed)) {
          if (sortedByPosition) {
            if (descriptorChildren == null) {
              descriptorChildren = new ArrayList<>();
            }
            descriptorChildren.add(((ProblemDescriptionNode)child).getDescriptor());
          } else {
            descriptors.add(((ProblemDescriptionNode)child).getDescriptor());
          }
        }
      }
      else {
        processChildDescriptorsDeep((InspectionTreeNode)child, descriptors, sortedByPosition, allowResolved, allowSuppressed, readOnlyFilesSink);
      }
    }

    if (descriptorChildren != null) {
      if (descriptorChildren.size() > 1) {
        Collections.sort(descriptorChildren, DESCRIPTOR_COMPARATOR);
      }
      if (readOnlyFilesSink != null) {
        collectReadOnlyFiles(descriptorChildren, readOnlyFilesSink);
      }

      descriptors.addAll(descriptorChildren);
    }
  }

  private static boolean isNodeValidAndIncluded(ProblemDescriptionNode node, boolean allowResolved, boolean allowSuppressed) {
    return node.isValid() && (allowResolved ||
                              (!node.isExcluded() &&
                               (!node.isAlreadySuppressedFromView() || (allowSuppressed && !node.getAvailableSuppressActions().isEmpty())) &&
                               !node.isQuickFixAppliedFromView()));
  }

  private void nodeStructureChanged(InspectionTreeNode node) {
    ((DefaultTreeModel)getModel()).nodeStructureChanged(node);
  }

  public void queueUpdate() {
    ((InspectionRootNode) getRoot()).getUpdater().update(true);
  }

  public void restoreExpansionAndSelection(boolean treeNodesMightChange) {
    myState.restoreExpansionAndSelection(this, treeNodesMightChange);
  }

  public InspectionTreeState getTreeState() {
    return myState;
  }

  public void setTreeState(@NotNull InspectionTreeState treeState) {
    myState = treeState;
  }

  private class ExpandListener implements TreeWillExpandListener {
    @Override
    public void treeWillExpand(TreeExpansionEvent event) {
      final InspectionTreeNode node = (InspectionTreeNode)event.getPath().getLastPathComponent();
      myState.getExpandedUserObjects().add(node.getUserObject());
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) {
      InspectionTreeNode node = (InspectionTreeNode)event.getPath().getLastPathComponent();
      myState.getExpandedUserObjects().remove(node.getUserObject());
    }
  }

  @NotNull
  public GlobalInspectionContextImpl getContext() {
    return myContext;
  }

  private InspectionGroupNode insertGroupNode(@NotNull String[] groupPath) {
    InspectionTreeNode currentNode = getRoot();

    for (int groupIdx = 0; groupIdx < groupPath.length; groupIdx++) {
      String subGroup = groupPath[groupIdx];

      InspectionTreeNode next = null;
      for (int i = 0; i < currentNode.getChildCount(); i++) {
        TreeNode child = currentNode.getChildAt(i);
        if (child instanceof InspectionGroupNode && ((InspectionGroupNode)child).getSubGroup().equals(subGroup)) {
          next = (InspectionTreeNode)child;
          break;
        }
      }

      if (next == null) {
        for (int i = groupIdx; i < groupPath.length; i++) {
          InspectionResultsView view = getContext().getView();
          if (view != null && !view.isDisposed()) {
            currentNode = currentNode.insertByOrder(new InspectionGroupNode(groupPath[i]), false);
          }
        }
        break;
      }
      else {
        currentNode = next;
      }
    }

    return (InspectionGroupNode)currentNode;
  }

  private static void collectReadOnlyFiles(@NotNull Collection<CommonProblemDescriptor> descriptors, @NotNull Set<VirtualFile> readOnlySink) {
    for (CommonProblemDescriptor descriptor : descriptors) {
      collectReadOnlyFiles(descriptor, readOnlySink);
    }
  }

  private static void collectReadOnlyFiles(@NotNull CommonProblemDescriptor descriptor, @NotNull Set<VirtualFile> readOnlySink) {
    if (descriptor instanceof ProblemDescriptor) {
      PsiElement psiElement = ((ProblemDescriptor)descriptor).getPsiElement();
      if (psiElement != null && !psiElement.isWritable()) {
        readOnlySink.add(psiElement.getContainingFile().getVirtualFile());
      }
    }
  }

  @NotNull
  private static String[] getGroupPath(@NotNull InspectionGroupNode node) {
    List<String> path = new ArrayList<>(2);
    while (true) {
      TreeNode parent = node.getParent();
      if (!(parent instanceof InspectionGroupNode)) break;
      node = (InspectionGroupNode)parent;
      path.add(node.getSubGroup());
    }
    return ArrayUtil.toStringArray(path);
  }
}
