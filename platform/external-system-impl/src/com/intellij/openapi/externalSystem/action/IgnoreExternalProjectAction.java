/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.externalSystem.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.project.ExternalConfigPathAware;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.DisposeAwareProjectChange;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.externalSystem.view.ExternalSystemNode;
import com.intellij.openapi.externalSystem.view.ModuleNode;
import com.intellij.openapi.externalSystem.view.ProjectNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Vladislav.Soroka
 * @since 3/21/2015
 */
public class IgnoreExternalProjectAction extends ExternalSystemToggleAction {

  private static final Logger LOG = Logger.getInstance(IgnoreExternalProjectAction.class);

  public IgnoreExternalProjectAction() {
    setText(ExternalSystemBundle.message("action.ignore.external.project.text", "external"));
    setDescription(ExternalSystemBundle.message("action.ignore.external.project.description"));
  }

  @Override
  public void setSelected(AnActionEvent e, boolean state) {
    final ProjectSystemId projectSystemId = getSystemId(e);
    setText(e, ExternalSystemBundle.message("action.ignore.external.project.text", projectSystemId.getReadableName()));

    final ExternalSystemNode<ExternalConfigPathAware> projectNode = getProjectNode(e);
    if (projectNode == null || projectNode.getData() == null) return;

    projectNode.setIgnored(state);

    final Project project = getProject(e);

    final String externalProjectPath = projectNode.getData().getLinkedExternalProjectPath();
    final ExternalProjectInfo externalProjectInfo =
      ExternalSystemUtil.getExternalProjectInfo(project, projectSystemId, externalProjectPath);
    if (externalProjectInfo == null || externalProjectInfo.getExternalProjectStructure() == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("external project data not found, path: %s, data: %s", externalProjectPath, externalProjectInfo));
      }
      return;
    }

    ExternalSystemApiUtil.executeProjectChangeAction(true, new DisposeAwareProjectChange(project) {
      @Override
      public void execute() {
        ProjectRootManagerEx.getInstanceEx(project).mergeRootsChangesDuring(new Runnable() {
          @Override
          public void run() {
            final DataNode<ProjectData> projectDataNode = externalProjectInfo.getExternalProjectStructure();
            ServiceManager.getService(ProjectDataManager.class).importData(projectDataNode, project, true);
          }
        });
      }
    });
  }

  @Override
  protected boolean isEnabled(AnActionEvent e) {
    if (!super.isEnabled(e)) return false;
    return getProjectNode(e) != null;
  }

  @Override
  protected boolean doIsSelected(AnActionEvent e) {
    final ExternalSystemNode projectNode = getProjectNode(e);
    if (projectNode == null) return false;
    return projectNode.isIgnored();
  }

  @Nullable
  private static ExternalSystemNode<ExternalConfigPathAware> getProjectNode(AnActionEvent e) {
    final List<ExternalSystemNode> selectedNodes = ExternalSystemDataKeys.SELECTED_NODES.getData(e.getDataContext());
    if (selectedNodes == null || selectedNodes.size() != 1) return null;
    final ExternalSystemNode<?> node = selectedNodes.get(0);
    //noinspection unchecked
    return (node instanceof ModuleNode || node instanceof ProjectNode) ? (ExternalSystemNode<ExternalConfigPathAware>)node : null;
  }
}
