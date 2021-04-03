/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.objectweb.asm.idea.plugin.action;

import com.intellij.ide.util.JavaAnonymousClassesHelper;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.ex.CompilerPathsEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.task.ProjectTask;
import com.intellij.task.ProjectTaskManager;
import com.intellij.task.ProjectTaskNotification;
import com.intellij.task.ProjectTaskResult;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.SmartList;
import com.intellij.util.containers.OrderedSet;
import com.intellij.util.io.URLUtil;
import com.sun.jna.StringArray;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.objectweb.asm.idea.plugin.common.Constants;
import org.objectweb.asm.idea.plugin.common.FileTypeExtension;
import org.objectweb.asm.idea.plugin.config.ASMPluginComponent;
import org.objectweb.asm.idea.plugin.config.ApplicationConfig;
import org.objectweb.asm.idea.plugin.util.GroovifiedTextifier;
import org.objectweb.asm.idea.plugin.view.BytecodeASMified;
import org.objectweb.asm.idea.plugin.view.BytecodeOutline;
import org.objectweb.asm.idea.plugin.view.GroovifiedView;

import reloc.org.objectweb.asm.ClassReader;
import reloc.org.objectweb.asm.util.ASMifier;
import reloc.org.objectweb.asm.util.TraceClassVisitor;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;


public class ShowBytecodeViewerAction extends AnAction {

    Module module;

    @Override
    public void update(final AnActionEvent e) {
        final VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        final Presentation presentation = e.getPresentation();
        if (project == null || virtualFile == null) {
            presentation.setEnabled(false);
            Logger.getInstance(ShowBytecodeViewerAction.class).error("project == null || virtualFile == null");
            return;
        }
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        presentation.setEnabled(psiFile instanceof PsiClassOwner);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        if (psiFile == null) {
            Logger.getInstance(ShowBytecodeViewerAction.class).error("psiFile == null");
            return;
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) {
            Logger.getInstance(ShowBytecodeViewerAction.class).error("virtualFile == null");
            return;
        }
        module = ProjectRootManager.getInstance(project).getFileIndex().getModuleForFile(virtualFile);

        ProjectTaskManager projectTaskManager = ProjectTaskManager.getInstance(project);
        ProjectTask buildTask = projectTaskManager.createModulesBuildTask(module, true, true, true);

        Logger.getInstance(ShowBytecodeViewerAction.class).info("run buildTask");
        projectTaskManager.run(buildTask).onSuccess(result -> {

            if (!result.hasErrors()) {
                PsiClassOwner file = (PsiClassOwner) PsiManager.getInstance(project).findFile(virtualFile);

                if (file == null) {
                    Logger.getInstance(ShowBytecodeViewerAction.class).error("file == null");
                    return;
                }
                VirtualFile fileOutputDirectory = getOutputFile(file, virtualFile);
                fileOutputDirectory.refresh(false, false);
                updateToolWindowContents(e.getProject(), fileOutputDirectory);
            }
        });
    }

    private VirtualFile getOutputFile(PsiClassOwner file, VirtualFile vFile) {
        // determine whether this is a production or test file
        Boolean isProduction = module.getModuleScope(false).contains(vFile);

        String pkg = file.getPackageName().replace('.', File.separatorChar);

        OrderedSet<String> possibleOutputDirectories = findModuleOutputDirectories(isProduction);

        VirtualFileSystem virtualFileManager = VirtualFileManager.getInstance().getFileSystem(URLUtil.FILE_PROTOCOL);

        Logger.getInstance(ShowBytecodeViewerAction.class).warn("pkg " + pkg);
        for (String possibleOutputDirectory : possibleOutputDirectories) {
            Logger.getInstance(ShowBytecodeViewerAction.class).warn("possibleOutputDirectory " + possibleOutputDirectory);
            String classFile = vFile.getNameWithoutExtension() + ".class";
            Logger.getInstance(ShowBytecodeViewerAction.class).warn("classFile " + classFile);
            String path = Paths.get(possibleOutputDirectory, pkg, classFile).toString();
            Logger.getInstance(ShowBytecodeViewerAction.class).warn("path " + path);
            VirtualFile file1 = virtualFileManager.refreshAndFindFileByPath(path);
            if (file1 != null) {
                return file1;
            }
        }

        return null;
    }

    private OrderedSet<String> findModuleOutputDirectories(Boolean production) {
        ArrayList<String> outputPaths = new ArrayList<String>();

        CompilerModuleExtension compilerExtension = CompilerModuleExtension.getInstance(module);
        CompilerProjectExtension compilerProjectExtension = CompilerProjectExtension.getInstance(module.getProject());
        if (production) {
            VirtualFile moduleFile = compilerExtension.getCompilerOutputPath();
            if (moduleFile != null) {
                outputPaths.add(moduleFile.getPath());
            } else {
                Logger.getInstance(ShowBytecodeViewerAction.class).warn("moduleFile == null ");
                VirtualFile projectFile = compilerProjectExtension.getCompilerOutput();
                if (projectFile != null) {
                    outputPaths.add(projectFile.getPath());
                }
            }
        } else {
            VirtualFile moduleFile = compilerExtension.getCompilerOutputPathForTests();
            if (moduleFile != null) {
                outputPaths.add(moduleFile.getPath());
            } else {
                VirtualFile projectFile = compilerProjectExtension.getCompilerOutput();
                if (projectFile != null) {
                    outputPaths.add(projectFile.getPath());
                }
            }
        }

        ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        for (OrderEnumerationHandler.Factory handlerFactory : OrderEnumerationHandler.EP_NAME.getExtensions()) {
            if (handlerFactory.isApplicable(module)) {
                OrderEnumerationHandler handler = handlerFactory.createHandler(module);
                List<String> outputUrls = new ArrayList<>();
                handler.addCustomModuleRoots(OrderRootType.CLASSES, moduleRootManager, outputUrls, production, !production);

                for (String outputUrl : outputUrls) {
                    outputPaths.add(VirtualFileManager.extractPath(outputUrl).replace('/', File.separatorChar));
                }
            }

        }
        return new OrderedSet(outputPaths);
    }

    /**
     * Reads the .class file, processes it through the ASM TraceVisitor and ASMifier to update the contents of the two
     * tabs of the tool window.
     *
     * @param project the project instance
     * @param file    the class file
     */
    private void updateToolWindowContents(final Project project, final VirtualFile file) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            BytecodeOutline bytecodeOutline = BytecodeOutline.getInstance(project);
            BytecodeASMified asmifiedView = BytecodeASMified.getInstance(project);
            GroovifiedView groovifiedView = GroovifiedView.getInstance(project);
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);


            if (file == null) {
                bytecodeOutline.setCode(file, Constants.NO_CLASS_FOUND);
                asmifiedView.setCode(file, Constants.NO_CLASS_FOUND);
                groovifiedView.setCode(file, Constants.NO_CLASS_FOUND);
                toolWindowManager.getToolWindow(Constants.PLUGIN_WINDOW_NAME).activate(null);
                return;
            } else {
                Logger.getInstance(ShowBytecodeViewerAction.class).warn("file " + file.toString());
            }

            StringWriter stringWriter = new StringWriter();
            ClassReader reader = null;
            try {
                file.refresh(false, false);
                reader = new ClassReader(file.contentsToByteArray());
            } catch (IOException e) {
                return;
            }
            int flags = 0;
            ApplicationConfig applicationConfig = ASMPluginComponent.getApplicationConfig();
            if (applicationConfig.isSkipDebug()) flags = flags | ClassReader.SKIP_DEBUG;
            if (applicationConfig.isSkipFrames()) flags = flags | ClassReader.SKIP_FRAMES;
            if (applicationConfig.isExpandFrames()) flags = flags | ClassReader.EXPAND_FRAMES;
            if (applicationConfig.isSkipCode()) flags = flags | ClassReader.SKIP_CODE;

            reader.accept(new TraceClassVisitor(new PrintWriter(stringWriter)), flags);
            bytecodeOutline.setCode(file, stringWriter.toString());

            stringWriter.getBuffer().setLength(0);
            reader.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(stringWriter)), flags);
            PsiFile psiFile = PsiFileFactory.getInstance(project).createFileFromText(Constants.FILE_NAME, FileTypeManager.getInstance().getFileTypeByExtension(FileTypeExtension.JAVA.getValue()), stringWriter.toString());
            CodeStyleManager.getInstance(project).reformat(psiFile);
            asmifiedView.setCode(file, psiFile.getText());

            stringWriter.getBuffer().setLength(0);
            reader.accept(new TraceClassVisitor(null, new GroovifiedTextifier(applicationConfig.getGroovyCodeStyle()), new PrintWriter(stringWriter)), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            groovifiedView.setCode(file, stringWriter.toString());


            toolWindowManager.getToolWindow(Constants.PLUGIN_WINDOW_NAME).activate(null);
        });
    }
}
