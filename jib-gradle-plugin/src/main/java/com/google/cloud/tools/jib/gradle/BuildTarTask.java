/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import static org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.filesystem.TempDirectoryProvider;
import com.google.cloud.tools.jib.plugins.common.BuildStepsExecutionException;
import com.google.cloud.tools.jib.plugins.common.ExtraDirectoryNotFoundException;
import com.google.cloud.tools.jib.plugins.common.HelpfulSuggestions;
import com.google.cloud.tools.jib.plugins.common.IncompatibleBaseImageJavaVersionException;
import com.google.cloud.tools.jib.plugins.common.InvalidAppRootException;
import com.google.cloud.tools.jib.plugins.common.InvalidContainerVolumeException;
import com.google.cloud.tools.jib.plugins.common.InvalidContainerizingModeException;
import com.google.cloud.tools.jib.plugins.common.InvalidCreationTimeException;
import com.google.cloud.tools.jib.plugins.common.InvalidFilesModificationTimeException;
import com.google.cloud.tools.jib.plugins.common.InvalidPlatformException;
import com.google.cloud.tools.jib.plugins.common.InvalidWorkingDirectoryException;
import com.google.cloud.tools.jib.plugins.common.MainClassInferenceException;
import com.google.cloud.tools.jib.plugins.common.PluginConfigurationProcessor;
import com.google.cloud.tools.jib.plugins.common.globalconfig.GlobalConfig;
import com.google.cloud.tools.jib.plugins.common.globalconfig.InvalidGlobalConfigException;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.War;
import org.gradle.api.tasks.options.Option;

/** Builds a container image to a tarball. */
public class BuildTarTask extends DefaultTask implements JibTask {

  private static final String HELPFUL_SUGGESTIONS_PREFIX = "Building image tarball failed";

  @Nullable private JibExtension jibExtension;

  /**
   * This will call the property {@code "jib"} so that it is the same name as the extension. This
   * way, the user would see error messages for missing configuration with the prefix {@code jib.}.
   *
   * @return the {@link JibExtension}.
   */
  @Nested
  @Nullable
  public JibExtension getJib() {
    return jibExtension;
  }

  /**
   * The target image can be overridden with the {@code --image} command line option.
   *
   * @param targetImage the name of the 'to' image.
   */
  @Option(option = "image", description = "The image reference for the target image")
  public void setTargetImage(String targetImage) {
    Preconditions.checkNotNull(jibExtension).getTo().setImage(targetImage);
  }

  /**
   * Returns a collection of all the files that jib includes in the image. Only used to calculate
   * UP-TO-DATE.
   *
   * @return a list of paths of input files
   */
  @InputFiles
  public FileCollection getInputFiles() {
    List<Path> extraDirectories =
        Preconditions.checkNotNull(jibExtension).getExtraDirectories().getPaths().stream()
            .map(ExtraDirectoryParameters::getFrom)
            .collect(Collectors.toList());
    return GradleProjectProperties.getInputFiles(
        getProject(), extraDirectories, jibExtension.getConfigurationName().get());
  }

  /**
   * The output file to check for task up-to-date.
   *
   * @return the output path
   */
  @OutputFile
  public String getOutputFile() {
    return Preconditions.checkNotNull(jibExtension).getOutputPaths().getTarPath().toString();
  }

  @Nested
  @Override
  public GradleData getGradleData() {
    return gradleData;
  }

  private final GradleData gradleData = getProject().getObjects().newInstance(GradleData.class);

  // todo: add inputs here. maybe we can add compiled classes/dependency output info here?
  //  this would let us avoid having to

  // @org.gradle.api.tasks.Optional
  // @InputFile
  // public RegularFileProperty getWarPath() {
  //  return warPath;
  // }
  // private final RegularFileProperty warPath = getProject().getObjects().fileProperty();
  //
  // @org.gradle.api.tasks.Optional
  // @InputFile
  // public RegularFileProperty getBootWarPath() {
  //  return bootWarPath;
  // }
  // private final RegularFileProperty bootWarPath = getProject().getObjects().fileProperty();

  @org.gradle.api.tasks.Optional
  @InputFile
  public RegularFileProperty getJar() {
    return jar;
  }

  private final RegularFileProperty jar = getProject().getObjects().fileProperty();

  @org.gradle.api.tasks.Optional
  @InputDirectory
  public DirectoryProperty getResourcesOutputDirectory() {
    return resourcesOutputDirectory;
  }

  private final DirectoryProperty resourcesOutputDirectory =
      getProject().getObjects().directoryProperty();

  @org.gradle.api.tasks.Optional
  @InputFiles
  public ConfigurableFileCollection getClassesOutputDirectories() {
    return classesOutputDirectories;
  }

  private final ConfigurableFileCollection classesOutputDirectories =
      getProject().getObjects().fileCollection();
  // @Nested
  // public Property<PackagingData> getPackagingData() {
  //  return packagingData;
  // }
  // private final Property<PackagingData> packagingData =
  // getProject().getObjects().property(PackagingData.class);

  @Inject
  public BuildTarTask() {
    // todo: figure out if this insanity is worth it?
    // getProject().getPluginManager().withPlugin("org.springframework.boot", (plugin) -> {
    //  TaskProvider<War> bootWarTask = TaskCommon.getBootWarTaskProvider(getProject());
    //  if (bootWarTask != null) { // should we use enabled here?
    //    Provider<War> warProvider = bootWarTask.map(it -> {
    //      if (it.isEnabled()) {
    //        return it;
    //      } else {
    //        return null;
    //      }
    //    });
    //    dependsOn(warProvider);
    //    warPath.set(warProvider.flatMap(AbstractArchiveTask::getArchiveFile));
    //  }
    // });
    // getProject().getPluginManager().withPlugin("war", (plugin) -> {
    //
    // });

    // todo: war inputs
    // todo: maybe used nested for the inputs
    // todo: abstract out these inputs

    /* JAR */
    TaskProvider<Jar> jarTaskProvider = getProject().getTasks().named("jar", Jar.class);
    jar.set(jarTaskProvider.flatMap(Jar::getArchiveFile));
    dependsOn(jar);
    /* JAR */

    /* SOURCES AND RESOURCES */
    SourceSetContainer sourceSetContainer =
        getProject().getExtensions().getByType(SourceSetContainer.class);
    NamedDomainObjectProvider<SourceSet> mainSourceSet =
        sourceSetContainer.named(MAIN_SOURCE_SET_NAME);
    resourcesOutputDirectory.fileProvider(
        mainSourceSet.map(
            it -> {
              File resourcesDir = it.getOutput().getResourcesDir();
              if (resourcesDir != null && resourcesDir.exists()) {
                // if there is no resources dir, it won't get copied.
                // therefore we need to check if it exists before we make it an input
                return resourcesDir;
              } else {
                return null;
              }
            }));
    dependsOn("processResources");

    JibExtension jibExtension = this.jibExtension;
    if (jibExtension != null) {
      FileCollection projectDependencies =
          getProject()
              .getObjects()
              .fileCollection()
              .from(
                  jibExtension
                      .getConfigurationName()
                      .map(
                          configurationName ->
                              getProject().getConfigurations().getByName(configurationName)
                                  .getResolvedConfiguration().getResolvedArtifacts().stream()
                                  .filter(
                                      artifact ->
                                          artifact.getId().getComponentIdentifier()
                                              instanceof ProjectComponentIdentifier)
                                  .map(ResolvedArtifact::getFile)
                                  .collect(Collectors.toList())));
    }

    // FileCollection classesOutputDirectories =
    //        mainSourceSet.getOutput().getClassesDirs().filter(File::exists);
    classesOutputDirectories.setFrom(mainSourceSet.map(it -> it.getOutput().getClassesDirs()));
    // classesOutputDirectories.from(
    // mainSourceSet.map(it -> {
    //  FileCollection resourcesDir = it.getOutput().getClassesDirs();
    //  if (resourcesDir != null && resourcesDir.exists()) {
    //    // if there is no resources dir, it won't get copied.
    //    // therefore we need to check if it exists before we make it an input
    //    return resourcesDir;
    //  } else {
    //    return null;
    //  }
    // }));

    /* SOURCES AND RESOURCES */

    // war is ok?
    gradleData.getIsWarProject().convention(false);
    getProject()
        .getPluginManager()
        .withPlugin(
            "war",
            (f) -> {
              gradleData.getIsWarProject().set(true);
              gradleData
                  .getWarFilePath()
                  .set(
                      getProject()
                          .getTasks()
                          .named("war", War.class)
                          .flatMap(AbstractArchiveTask::getArchiveFile));
            });

    System.out.println("building tar task brah");
  }

  /**
   * Task Action, builds an image to tar file.
   *
   * @throws IOException if an error occurs creating the jib runner
   * @throws BuildStepsExecutionException if an error occurs while executing build steps
   * @throws CacheDirectoryCreationException if a new cache directory could not be created
   * @throws MainClassInferenceException if a main class could not be found
   * @throws InvalidGlobalConfigException if the global config file is invalid
   */
  @TaskAction
  public void buildTar()
      throws BuildStepsExecutionException, IOException, CacheDirectoryCreationException,
          MainClassInferenceException, InvalidGlobalConfigException {
    // Asserts required @Input parameters are not null.
    Preconditions.checkNotNull(jibExtension);
    TaskCommon.disableHttpLogging();
    TempDirectoryProvider tempDirectoryProvider = new TempDirectoryProvider();

    // todo: execute on inputs
    //  that means calling the plugins' entrypoints to make them do their tasks. after all,
    //  I guess we're the task that owns them?

    GradleProjectProperties projectProperties =
        GradleProjectProperties.getForProject(
            getProject(),
            jar.map(i -> i.getAsFile().toPath()).getOrNull(),
            resourcesOutputDirectory.map(i -> i.getAsFile().toPath()).getOrNull(),
            classesOutputDirectories,
            gradleData,
            getLogger(),
            tempDirectoryProvider,
            jibExtension.getConfigurationName().get());
    GlobalConfig globalConfig = GlobalConfig.readConfig();
    Future<Optional<String>> updateCheckFuture =
        TaskCommon.newUpdateChecker(projectProperties, globalConfig, getLogger());
    try {
      PluginConfigurationProcessor.createJibBuildRunnerForTarImage(
              new GradleRawConfiguration(jibExtension),
              ignored -> Optional.empty(),
              projectProperties,
              globalConfig,
              new GradleHelpfulSuggestions(HELPFUL_SUGGESTIONS_PREFIX))
          .runBuild();

    } catch (InvalidAppRootException ex) {
      throw new GradleException(
          "container.appRoot is not an absolute Unix-style path: " + ex.getInvalidPathValue(), ex);

    } catch (InvalidContainerizingModeException ex) {
      throw new GradleException(
          "invalid value for containerizingMode: " + ex.getInvalidContainerizingMode(), ex);

    } catch (InvalidWorkingDirectoryException ex) {
      throw new GradleException(
          "container.workingDirectory is not an absolute Unix-style path: "
              + ex.getInvalidPathValue(),
          ex);
    } catch (InvalidPlatformException ex) {
      throw new GradleException(
          "from.platforms contains a platform configuration that is missing required values or has invalid values: "
              + ex.getMessage()
              + ": "
              + ex.getInvalidPlatform(),
          ex);

    } catch (InvalidContainerVolumeException ex) {
      throw new GradleException(
          "container.volumes is not an absolute Unix-style path: " + ex.getInvalidVolume(), ex);

    } catch (InvalidFilesModificationTimeException ex) {
      throw new GradleException(
          "container.filesModificationTime should be an ISO 8601 date-time (see "
              + "DateTimeFormatter.ISO_DATE_TIME) or special keyword \"EPOCH_PLUS_SECOND\": "
              + ex.getInvalidFilesModificationTime(),
          ex);

    } catch (InvalidCreationTimeException ex) {
      throw new GradleException(
          "container.creationTime should be an ISO 8601 date-time (see "
              + "DateTimeFormatter.ISO_DATE_TIME) or a special keyword (\"EPOCH\", "
              + "\"USE_CURRENT_TIMESTAMP\"): "
              + ex.getInvalidCreationTime(),
          ex);

    } catch (JibPluginExtensionException ex) {
      String extensionName = ex.getExtensionClass().getName();
      throw new GradleException(
          "error running extension '" + extensionName + "': " + ex.getMessage(), ex);

    } catch (IncompatibleBaseImageJavaVersionException ex) {
      throw new GradleException(
          HelpfulSuggestions.forIncompatibleBaseImageJavaVersionForGradle(
              ex.getBaseImageMajorJavaVersion(), ex.getProjectMajorJavaVersion()),
          ex);

    } catch (InvalidImageReferenceException ex) {
      throw new GradleException(
          HelpfulSuggestions.forInvalidImageReference(ex.getInvalidReference()), ex);

    } catch (ExtraDirectoryNotFoundException ex) {
      throw new GradleException(
          "extraDirectories.paths contain \"from\" directory that doesn't exist locally: "
              + ex.getPath(),
          ex);
    } finally {
      tempDirectoryProvider.close();
      TaskCommon.finishUpdateChecker(projectProperties, updateCheckFuture);
      projectProperties.waitForLoggingThread();
    }
  }

  @Override
  public BuildTarTask setJibExtension(JibExtension jibExtension) {
    this.jibExtension = jibExtension;
    return this;
  }
}
