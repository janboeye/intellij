/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.java.fastbuild;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.idea.common.guava.GuavaHelper.toImmutableMap;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.idea.blaze.base.async.executor.ProgressiveTaskWithProgressIndicator;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.command.BlazeInvocationContext;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.console.BlazeConsoleLineProcessorProvider;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.issueparser.IssueOutputFilter;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Kind;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.ScopedTask;
import com.google.idea.blaze.base.scope.output.StatusOutput;
import com.google.idea.blaze.base.scope.scopes.BlazeConsoleScope;
import com.google.idea.blaze.base.scope.scopes.IssuesScope;
import com.google.idea.blaze.base.settings.BlazeUserSettings;
import com.google.idea.blaze.base.settings.BlazeUserSettings.BlazeConsolePopupBehavior;
import com.google.idea.blaze.base.sync.aspects.BuildResult;
import com.google.idea.blaze.base.sync.aspects.BuildResult.Status;
import com.google.idea.blaze.base.sync.aspects.IdeInfoFromProtobuf;
import com.google.idea.blaze.base.sync.aspects.strategy.AspectStrategy;
import com.google.idea.blaze.base.sync.aspects.strategy.AspectStrategy.OutputGroup;
import com.google.idea.blaze.base.sync.aspects.strategy.AspectStrategyProvider;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.java.fastbuild.FastBuildState.BuildOutput;
import com.google.idea.common.concurrency.ConcurrencyUtil;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.annotation.Nullable;

final class FastBuildServiceImpl implements FastBuildService {

  private static final ImmutableSet<Kind> SUPPORTED_KINDS = ImmutableSet.of(Kind.JAVA_TEST);

  private final Project project;
  private final ProjectViewManager projectViewManager;
  private final BlazeProjectDataManager projectDataManager;
  private final ChangeListManager changeListManager;
  private final ProjectManager projectManager;
  private final FastBuildIncrementalCompiler incrementalCompiler;

  private final ConcurrentHashMap<Label, FastBuildState> builds;

  FastBuildServiceImpl(
      Project project,
      ProjectViewManager projectViewManager,
      BlazeProjectDataManager projectDataManager,
      ChangeListManager changeListManager,
      ProjectManager projectManager,
      FastBuildIncrementalCompiler incrementalCompiler) {
    this.project = project;
    this.projectViewManager = projectViewManager;
    this.projectDataManager = projectDataManager;
    this.changeListManager = changeListManager;
    this.projectManager = projectManager;
    this.incrementalCompiler = incrementalCompiler;
    this.builds = new ConcurrentHashMap<>();
  }

  @Override
  public boolean supportsFastBuilds(Kind kind) {
    return FastBuildService.enabled.getValue() && SUPPORTED_KINDS.contains(kind);
  }

  @Override
  public Future<FastBuildInfo> createBuild(
      Label label, String blazeBinaryPath, List<String> blazeFlags) throws FastBuildException {

    try {
      checkLabelIsSupported(label);
      FastBuildParameters buildParameters = generateBuildParameters(blazeBinaryPath, blazeFlags);
      FastBuildState buildState =
          builds.compute(
              label, (unused, buildInfo) -> updateBuild(label, buildParameters, buildInfo));
      return transform(
          buildState.newBuildOutput(),
          buildOutput ->
              FastBuildInfo.create(
                  label,
                  ImmutableList.of(buildState.compilerOutputDirectory(), buildOutput.deployJar()),
                  buildOutput.targetMap()),
          MoreExecutors.directExecutor());
    } catch (FastBuildTunnelException e) {
      throw e.asFastBuildException();
    }
  }

  private void checkLabelIsSupported(Label label) {
    BlazeProjectData blazeProjectData = projectDataManager.getBlazeProjectData();
    checkState(blazeProjectData != null, "this is not a blaze project");
    TargetIdeInfo ideInfo = blazeProjectData.targetMap.get(TargetKey.forPlainTarget(label));
    checkArgument(ideInfo != null, "label %s is not found, run a blaze sync?", label);
    checkArgument(
        supportsFastBuilds(ideInfo.kind),
        "fast builds are not supported for %s targets",
        ideInfo.kind);
  }

  private FastBuildParameters generateBuildParameters(
      String blazeBinaryPath, List<String> userBlazeFlags) {

    ProjectViewSet projectViewSet = projectViewManager.getProjectViewSet();
    List<String> projectBlazeFlags =
        BlazeFlags.blazeFlags(
            project, projectViewSet, BlazeCommandName.BUILD, BlazeInvocationContext.NonSync);
    return FastBuildParameters.builder()
        .setBlazeBinary(blazeBinaryPath)
        .addBlazeFlags(projectBlazeFlags)
        .addBlazeFlags(userBlazeFlags)
        .build();
  }

  private FastBuildState updateBuild(
      Label label,
      FastBuildParameters buildParameters,
      @Nullable FastBuildState existingBuildState) {
    if (existingBuildState != null && !existingBuildState.newBuildOutput().isDone()) {
      // Don't start a new build if an existing one is still running.
      return existingBuildState;
    }

    // We're adding a new entry to the map, so make sure to also mark it for cleanup.
    if (existingBuildState == null) {
      CleanupFastBuildData cleanup = new CleanupFastBuildData(label);
      projectManager.addProjectManagerListener(project, cleanup);
      Runtime.getRuntime()
          .addShutdownHook(new Thread(() -> cleanup.projectClosed(/* project */ null)));
    }

    BuildOutput completedBuildOutput = getCompletedBuild(existingBuildState);
    Set<File> modifiedFiles = getVcsModifiedFiles();
    if (completedBuildOutput == null) {
      File compileDirectory = createCompilerOutputDirectory();
      return FastBuildState.create(
          buildDeployJar(label, buildParameters), compileDirectory, buildParameters, modifiedFiles);
    } else {
      existingBuildState =
          existingBuildState
              .withAdditionalModifiedFiles(modifiedFiles)
              .withCompletedBuildOutput(completedBuildOutput);
      return existingBuildState.withNewBuildOutput(
          incrementalCompiler.compile(label, existingBuildState));
    }
  }

  private File createCompilerOutputDirectory() {
    try {
      return Files.createTempDirectory("ide-fastbuild-").toFile();
    } catch (IOException e) {
      throw new FastBuildTunnelException(e);
    }
  }

  private class CleanupFastBuildData implements ProjectManagerListener {

    private final Label label;

    private CleanupFastBuildData(Label label) {
      this.label = label;
    }

    @Override
    public void projectClosed(Project project) {
      FastBuildState build = builds.remove(label);
      if (build != null) {
        FileUtil.delete(build.compilerOutputDirectory());
      }
    }
  }

  private Set<File> getVcsModifiedFiles() {
    Set<File> modifiedPaths = new HashSet<>();
    changeListManager.invokeAfterUpdate(
        () -> addAllModifiedPaths(modifiedPaths),
        InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE,
        "Retrieving list of modified files",
        ModalityState.NON_MODAL);
    return modifiedPaths;
  }

  private void addAllModifiedPaths(Set<File> modifiedPaths) {
    changeListManager
        .getAllChanges()
        .stream()
        .flatMap(change -> Stream.of(change.getBeforeRevision(), change.getAfterRevision()))
        .filter(Objects::nonNull)
        .map(ContentRevision::getFile)
        .filter(filePath -> !filePath.isNonLocal() && !filePath.isDirectory())
        .forEach(filePath -> modifiedPaths.add(filePath.getIOFile()));
  }

  @Nullable
  private static BuildOutput getCompletedBuild(@Nullable FastBuildState buildState) {
    if (buildState == null) {
      return null;
    }

    BuildOutput buildOutput;
    try {
      buildOutput = buildState.newBuildOutput().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("InterruptedException caught when calling get() on a done future");
    } catch (CancellationException | ExecutionException e) {
      // Whoever initially launched this build will log the exception, so we don't need to worry
      // about it.
      buildOutput = buildState.completedBuildOutput().orElse(null);
    }

    return buildOutput != null && buildOutput.deployJar().exists() ? buildOutput : null;
  }

  private ListenableFuture<FastBuildState.BuildOutput> buildDeployJar(
      Label label, FastBuildParameters buildParameters) {

    Label deployJarLabel = createDeployJarLabel(label);
    WorkspaceRoot workspaceRoot = WorkspaceRoot.fromProject(project);

    // TODO(plumpy): this assumes we're running this build as part of a run action. I try not to
    // make that assumption anywhere else, so this should be supplied by the caller.
    BlazeConsolePopupBehavior consolePopupBehavior =
        BlazeUserSettings.getInstance().getSuppressConsoleForRunAction()
            ? BlazeConsolePopupBehavior.NEVER
            : BlazeConsolePopupBehavior.ALWAYS;

    AspectStrategy aspectStrategy =
        AspectStrategyProvider.findAspectStrategy(
            projectDataManager.getBlazeProjectData().blazeVersionData);
    BuildResultHelper buildResultHelper =
        BuildResultHelper.forFiles(
            file ->
                file.endsWith(deployJarLabel.targetName().toString())
                    || aspectStrategy.getAspectOutputFilePredicate().test(file));

    ListenableFuture<BuildResult> buildResultFuture =
        ProgressiveTaskWithProgressIndicator.builder(project)
            .submitTaskWithResult(
                new ScopedTask<BuildResult>() {
                  @Override
                  protected BuildResult execute(BlazeContext context) {
                    context
                        .push(new IssuesScope(project, /* focusProblemsViewOnIssue */ true))
                        .push(
                            new BlazeConsoleScope.Builder(project)
                                .setPopupBehavior(consolePopupBehavior)
                                .addConsoleFilters(
                                    new IssueOutputFilter(
                                        project,
                                        workspaceRoot,
                                        BlazeInvocationContext.NonSync,
                                        true))
                                .build());

                    context.output(
                        new StatusOutput(
                            "Building base deploy jar for fast builds: "
                                + deployJarLabel.targetName()));

                    BlazeCommand.Builder command =
                        BlazeCommand.builder(buildParameters.blazeBinary(), BlazeCommandName.BUILD)
                            .addTargets(label)
                            .addTargets(deployJarLabel)
                            .addBlazeFlags(buildParameters.blazeFlags())
                            .addBlazeFlags(buildResultHelper.getBuildFlags());

                    List<String> outputGroups = new ArrayList<>();
                    outputGroups.add("default"); // needed to retrieve the deploy jar
                    outputGroups.addAll(
                        aspectStrategy.getOutputGroups(
                            OutputGroup.INFO, ImmutableSet.of(LanguageClass.JAVA)));
                    aspectStrategy.addAspectAndOutputGroups(command, outputGroups);

                    int exitCode =
                        ExternalTask.builder(workspaceRoot)
                            .addBlazeCommand(command.build())
                            .context(context)
                            .stderr(
                                LineProcessingOutputStream.of(
                                    BlazeConsoleLineProcessorProvider.getAllStderrLineProcessors(
                                        context)))
                            .build()
                            .run();
                    return BuildResult.fromExitCode(exitCode);
                  }
                });
    ListenableFuture<BuildOutput> buildOutputFuture =
        transform(
            buildResultFuture,
            result -> {
              if (result.status != Status.SUCCESS) {
                throw new RuntimeException("Blaze failure building deploy jar");
              }
              ImmutableList<File> deployJarArtifacts =
                  buildResultHelper.getBuildArtifactsForTarget(deployJarLabel);
              checkState(deployJarArtifacts.size() == 1);
              File deployJar = deployJarArtifacts.get(0);

              ImmutableList<File> ideInfoFiles =
                  buildResultHelper.getArtifactsForOutputGroups(
                      aspectStrategy.getOutputGroups(
                          OutputGroup.INFO, ImmutableSet.of(LanguageClass.JAVA)));

              ImmutableMap<TargetKey, TargetIdeInfo> targetMap =
                  ideInfoFiles
                      .stream()
                      .map(file -> readTargetIdeInfo(aspectStrategy, file))
                      .filter(Objects::nonNull)
                      .collect(toImmutableMap(ideInfo -> ideInfo.key, i -> i));
              return BuildOutput.create(deployJar, new TargetMap(targetMap));
            },
            ConcurrencyUtil.getAppExecutorService());
    buildOutputFuture.addListener(
        buildResultHelper::close, ConcurrencyUtil.getAppExecutorService());
    return buildOutputFuture;
  }

  private static TargetIdeInfo readTargetIdeInfo(AspectStrategy aspectStrategy, File file) {
    try {
      return IdeInfoFromProtobuf.makeTargetIdeInfo(aspectStrategy.readAspectFile(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Label createDeployJarLabel(Label label) {
    return Label.create(label + "_deploy.jar");
  }

  // #api171: this can go away. In Guava 19, there is a second overload of Futures.transform that
  // prevents you from using a lambda for the function argument.
  public static <I, O> ListenableFuture<O> transform(
      ListenableFuture<I> input, Function<? super I, ? extends O> function, Executor executor) {
    return Futures.transform(input, function, executor);
  }

  private static class FastBuildTunnelException extends RuntimeException {

    FastBuildTunnelException(Throwable cause) {
      super(cause);
    }

    FastBuildException asFastBuildException() {
      Throwable cause = getCause();
      return cause instanceof FastBuildException
          ? (FastBuildException) cause
          : new FastBuildException(cause);
    }
  }
}
