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

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.JavaContainerBuilder;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan;
import com.google.cloud.tools.jib.event.events.ProgressEvent;
import com.google.cloud.tools.jib.event.events.TimerEvent;
import com.google.cloud.tools.jib.event.progress.ProgressEventHandler;
import com.google.cloud.tools.jib.filesystem.DirectoryWalker;
import com.google.cloud.tools.jib.filesystem.TempDirectoryProvider;
import com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension;
import com.google.cloud.tools.jib.plugins.common.ContainerizingMode;
import com.google.cloud.tools.jib.plugins.common.JavaContainerBuilderHelper;
import com.google.cloud.tools.jib.plugins.common.PluginExtensionLogger;
import com.google.cloud.tools.jib.plugins.common.ProjectProperties;
import com.google.cloud.tools.jib.plugins.common.PropertyNames;
import com.google.cloud.tools.jib.plugins.common.RawConfiguration.ExtensionConfiguration;
import com.google.cloud.tools.jib.plugins.common.TimerEventHandler;
import com.google.cloud.tools.jib.plugins.common.ZipUtil;
import com.google.cloud.tools.jib.plugins.common.logging.ConsoleLogger;
import com.google.cloud.tools.jib.plugins.common.logging.ConsoleLoggerBuilder;
import com.google.cloud.tools.jib.plugins.common.logging.ProgressDisplayGenerator;
import com.google.cloud.tools.jib.plugins.common.logging.SingleThreadedExecutor;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import com.google.cloud.tools.jib.plugins.extension.NullExtension;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/** Obtains information about a Gradle {@link Project} that uses Jib. */
public class GradleProjectProperties implements ProjectProperties {

  /** Used to generate the User-Agent header and history metadata. */
  private static final String TOOL_NAME = "jib-gradle-plugin";

  /** Used to generate the User-Agent header and history metadata and verify versions. */
  static final String TOOL_VERSION =
      GradleProjectProperties.class.getPackage().getImplementationVersion();

  /** Used for logging during main class inference. */
  private static final String PLUGIN_NAME = "jib";

  /** Used for logging during main class inference. */
  private static final String JAR_PLUGIN_NAME = "'jar' task";

  /** Name of the `main` {@link SourceSet} to use as source files. */
  private static final String MAIN_SOURCE_SET_NAME = "main";

  private static final Duration LOGGING_THREAD_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);

  /**
   * Generate an instance for a gradle project.
   *
   * @param logger a gradle logging instance to use for logging during the build
   * @param tempDirectoryProvider for scratch space during the build
   * @param configurationName the configuration of which the dependencies should be packed into the
   *     container
   * @return a GradleProjectProperties instance to use in a jib build
   */
  public static GradleProjectProperties getForProject(
      ObjectFactory objects,
      ProjectLayout layout,
      GradleProjectParameters gradleProjectParameters,
      Logger logger,
      TempDirectoryProvider tempDirectoryProvider,
      String configurationName) {
    Supplier<List<JibGradlePluginExtension<?>>> extensionLoader =
        () -> {
          List<JibGradlePluginExtension<?>> extensions = new ArrayList<>();
          for (JibGradlePluginExtension<?> extension :
              ServiceLoader.load(JibGradlePluginExtension.class)) {
            extensions.add(extension);
          }
          return extensions;
        };
    return new GradleProjectProperties(
        objects,
        layout,
        gradleProjectParameters,
        logger,
        tempDirectoryProvider,
        extensionLoader,
        configurationName);
  }

  public static GradleProjectProperties getForProject(
      Project project,
      Logger logger,
      TempDirectoryProvider tempDirectoryProvider,
      String configurationName) {
    GradleProjectParameters gradleData = new GradleProjectParameters(project.getObjects(), project);
    return getForProject(
        project.getObjects(),
        project.getLayout(),
        gradleData,
        logger,
        tempDirectoryProvider,
        configurationName);
  }

  String getWarFilePath() {
    return gradleProjectParameters.getWarFilePath().map(it -> it.getAsFile().getPath()).getOrNull();
  }

  private static boolean isProgressFooterEnabled(ConsoleOutput consoleOutput) {
    if ("plain".equals(System.getProperty(PropertyNames.CONSOLE))) {
      return false;
    }

    switch (consoleOutput) {
      case Plain:
        return false;

      case Auto:
        // Enables progress footer when ANSI is supported (Windows or TERM not 'dumb').
        // Unlike jib-maven-plugin, we cannot test "System.console() != null".
        // https://github.com/GoogleContainerTools/jib/issues/2920#issuecomment-749234458
        return Os.isFamily(Os.FAMILY_WINDOWS) || !"dumb".equals(System.getenv("TERM"));

      default:
        return true;
    }
  }

  // private final Project project;
  private final ObjectFactory objects;
  private final ProjectLayout layout;
  private final boolean isOffline;
  private final SingleThreadedExecutor singleThreadedExecutor = new SingleThreadedExecutor();
  private final ConsoleLogger consoleLogger;
  private final TempDirectoryProvider tempDirectoryProvider;
  private final Supplier<List<JibGradlePluginExtension<?>>> extensionLoader;
  private final String configurationName;

  @VisibleForTesting
  GradleProjectProperties(
      Project project,
      Logger logger,
      TempDirectoryProvider tempDirectoryProvider,
      Supplier<List<JibGradlePluginExtension<?>>> extensionLoader,
      String configurationName) {
    throw new RuntimeException("onoz");
    /*
    this(
        project,
        null,
        null,
        null,
        null,
        logger,
        tempDirectoryProvider,
        extensionLoader,
        configurationName);
     */
  }

  private final GradleProjectParameters gradleProjectParameters;

  @VisibleForTesting
  GradleProjectProperties(
      ObjectFactory objects,
      ProjectLayout layout,
      GradleProjectParameters gradleProjectParameters,
      Logger logger,
      TempDirectoryProvider tempDirectoryProvider,
      Supplier<List<JibGradlePluginExtension<?>>> extensionLoader,
      String configurationName) {
    this.gradleProjectParameters = gradleProjectParameters;
    // this.project = project;
    // todo: pull this up one level
    this.objects = objects;
    this.layout = layout;
    this.isOffline = gradleProjectParameters.isOffline();
    this.tempDirectoryProvider = tempDirectoryProvider;
    this.extensionLoader = extensionLoader;
    this.configurationName = configurationName;
    ConsoleLoggerBuilder consoleLoggerBuilder =
        (isProgressFooterEnabled(gradleProjectParameters.getConsoleOutput())
                ? ConsoleLoggerBuilder.rich(singleThreadedExecutor, false)
                : ConsoleLoggerBuilder.plain(singleThreadedExecutor).progress(logger::lifecycle))
            .lifecycle(logger::lifecycle);
    if (logger.isDebugEnabled()) {
      consoleLoggerBuilder.debug(logger::debug);
    }
    if (logger.isInfoEnabled()) {
      consoleLoggerBuilder.info(logger::info);
    }
    if (logger.isWarnEnabled()) {
      consoleLoggerBuilder.warn(logger::warn);
    }
    if (logger.isErrorEnabled()) {
      consoleLoggerBuilder.error(logger::error);
    }
    consoleLogger = consoleLoggerBuilder.build();
  }

  @Override
  public JibContainerBuilder createJibContainerBuilder(
      JavaContainerBuilder javaContainerBuilder, ContainerizingMode containerizingMode) {
    try {
      FileCollection projectDependencies = gradleProjectParameters.getProjectDependencies();

      if (isWarProject()) {
        String warFilePath = getWarFilePath();
        log(LogEvent.info("WAR project identified, creating WAR image from: " + warFilePath));
        Path explodedWarPath = tempDirectoryProvider.newDirectory();
        ZipUtil.unzip(Paths.get(warFilePath), explodedWarPath);
        return JavaContainerBuilderHelper.fromExplodedWar(
            javaContainerBuilder,
            explodedWarPath,
            projectDependencies.getFiles().stream().map(File::getName).collect(Collectors.toSet()));
      }

      FileCollection classesOutputDirectories =
          gradleProjectParameters.getClassesOutputDirectories().filter(File::exists);
      FileCollection allFiles = gradleProjectParameters.getAllFiles().filter(File::exists);

      Path resourcesOutputDirectory =
          gradleProjectParameters
              .getResourcesOutputDirectory()
              .map(i -> i.getAsFile().toPath())
              .getOrNull();
      FileCollection nonProjectDependencies =
          allFiles
              .minus(classesOutputDirectories)
              .minus(projectDependencies)
              .filter(file -> !file.toPath().equals(resourcesOutputDirectory));

      FileCollection snapshotDependencies =
          nonProjectDependencies.filter(file -> file.getName().contains("SNAPSHOT"));
      FileCollection dependencies = nonProjectDependencies.minus(snapshotDependencies);

      // Adds dependency files
      javaContainerBuilder
          .addDependencies(
              dependencies.getFiles().stream().map(File::toPath).collect(Collectors.toList()))
          .addSnapshotDependencies(
              snapshotDependencies.getFiles().stream()
                  .map(File::toPath)
                  .collect(Collectors.toList()))
          .addProjectDependencies(
              projectDependencies.getFiles().stream()
                  .map(File::toPath)
                  .collect(Collectors.toList()));

      switch (containerizingMode) {
        case EXPLODED:
          // Adds resource files
          if (resourcesOutputDirectory != null) {
            javaContainerBuilder.addResources(resourcesOutputDirectory);
          }

          // Adds class files
          for (File classesOutputDirectory : classesOutputDirectories) {
            javaContainerBuilder.addClasses(classesOutputDirectory.toPath());
          }
          if (classesOutputDirectories.isEmpty()) {
            log(LogEvent.warn("No classes files were found - did you compile your project?"));
          }
          break;

        case PACKAGED:
          // Add a JAR
          Path jarPath = gradleProjectParameters.getJarPath().get().getAsFile().toPath();

          log(LogEvent.debug("Using JAR: " + jarPath));
          javaContainerBuilder.addToClasspath(jarPath);
          break;

        default:
          throw new IllegalStateException("unknown containerizing mode: " + containerizingMode);
      }

      return javaContainerBuilder.toContainerBuilder();

    } catch (IOException ex) {
      throw new GradleException("Obtaining project build output files failed", ex);
    }
  }

  @Override
  public List<Path> getClassFiles() throws IOException {
    // TODO: Consolidate with createJibContainerBuilder
    FileCollection classesOutputDirectories =
        gradleProjectParameters.getClassesOutputDirectories().filter(File::exists);
    List<Path> classFiles = new ArrayList<>();
    for (File classesOutputDirectory : classesOutputDirectories) {
      classFiles.addAll(new DirectoryWalker(classesOutputDirectory.toPath()).walk());
    }
    return classFiles;
  }

  @Override
  public List<Path> getDependencies() {
    List<Path> dependencies = new ArrayList<>();
    // To be on the safe side with the order, calling "forEach" first (no filtering operations).
    gradleProjectParameters
        .getAllFiles()
        .forEach(
            file -> {
              if (file.exists()
                  && file.isFile()
                  && file.getName().toLowerCase(Locale.US).endsWith(".jar")) {
                dependencies.add(file.toPath());
              }
            });
    return dependencies;
  }

  @Override
  public void waitForLoggingThread() {
    singleThreadedExecutor.shutDownAndAwaitTermination(LOGGING_THREAD_SHUTDOWN_TIMEOUT);
  }

  @Override
  public void configureEventHandlers(Containerizer containerizer) {
    containerizer
        .addEventHandler(LogEvent.class, this::log)
        .addEventHandler(
            TimerEvent.class, new TimerEventHandler(message -> log(LogEvent.debug(message))))
        .addEventHandler(
            ProgressEvent.class,
            new ProgressEventHandler(
                update -> {
                  List<String> footer =
                      ProgressDisplayGenerator.generateProgressDisplay(
                          update.getProgress(), update.getUnfinishedLeafTasks());
                  footer.add("");
                  consoleLogger.setFooter(footer);
                }));
  }

  @Override
  public void log(LogEvent logEvent) {
    consoleLogger.log(logEvent.getLevel(), logEvent.getMessage());
  }

  @Override
  public String getToolName() {
    return TOOL_NAME;
  }

  @Override
  public String getToolVersion() {
    return TOOL_VERSION;
  }

  @Override
  public String getPluginName() {
    return PLUGIN_NAME;
  }

  @Nullable
  @Override
  public String getMainClassFromJarPlugin() {
    return gradleProjectParameters.getMainClassFromJarPlugin().getOrNull();
    // todo: copy this logic into upper levels
    /*
    Jar jarTask = (Jar) project.getTasks().findByName("jar");
    if (jarTask == null) {
      return null;
    }

    Object value = jarTask.getManifest().getAttributes().get("Main-Class");

    if (value instanceof Provider) {
      value = ((Provider<?>) value).getOrNull();
    }

    if (value instanceof String) {
      return (String) value;
    }

    if (value == null) {
      return null;
    }

    return String.valueOf(value);
     */
  }

  @Override
  public Path getDefaultCacheDirectory() {
    return layout
        .getBuildDirectory()
        .map(Directory::getAsFile)
        .get()
        .toPath()
        .resolve(CACHE_DIRECTORY_NAME);
  }

  @Override
  public String getJarPluginName() {
    return JAR_PLUGIN_NAME;
  }

  @Override
  public boolean isWarProject() {
    return gradleProjectParameters.getIsWarProject().get();
  }

  /**
   * Returns the input files for a task. These files include the gradle {@link
   * org.gradle.api.artifacts.Configuration}, output directories (classes, resources, etc.) of the
   * main {@link org.gradle.api.tasks.SourceSet}, and any extraDirectories defined by the user to
   * include in the container.
   *
   * @param project the gradle project
   * @param extraDirectories the image's configured extra directories
   * @return the input files
   */
  @VisibleForTesting
  static FileCollection getInputFiles(
      Project project, List<Path> extraDirectories, String configurationName) {
    List<FileCollection> dependencyFileCollections = new ArrayList<>();
    dependencyFileCollections.add(project.getConfigurations().getByName(configurationName));
    // Output directories (classes and resources) from main SourceSet are added
    // so that BuildTarTask picks up changes in these and do not skip task
    SourceSetContainer sourceSetContainer =
        project.getExtensions().getByType(SourceSetContainer.class);
    SourceSet mainSourceSet = sourceSetContainer.getByName(MAIN_SOURCE_SET_NAME);
    dependencyFileCollections.add(mainSourceSet.getOutput());

    extraDirectories.stream()
        .filter(Files::exists)
        .map(Path::toFile)
        .map(project::files)
        .forEach(dependencyFileCollections::add);

    return project.files(dependencyFileCollections);
  }

  @Override
  public String getName() {
    return gradleProjectParameters.getName().get();
  }

  @Override
  public String getVersion() {
    return gradleProjectParameters.getVersion().get();
  }

  @Override
  public int getMajorJavaVersion() {
    JavaVersion version =
        gradleProjectParameters.getTargetCompatibility().getOrElse(JavaVersion.current());
    return Integer.valueOf(version.getMajorVersion());
  }

  @Override
  public boolean isOffline() {
    return isOffline;
  }

  @Override
  public JibContainerBuilder runPluginExtensions(
      List<? extends ExtensionConfiguration> extensionConfigs,
      JibContainerBuilder jibContainerBuilder)
      throws JibPluginExtensionException {
    if (extensionConfigs.isEmpty()) {
      log(LogEvent.debug("No Jib plugin extensions configured to load"));
      return jibContainerBuilder;
    }

    List<JibGradlePluginExtension<?>> loadedExtensions = extensionLoader.get();
    JibGradlePluginExtension<?> extension = null;
    ContainerBuildPlan buildPlan = jibContainerBuilder.toContainerBuildPlan();
    try {
      for (ExtensionConfiguration config : extensionConfigs) {
        extension = findConfiguredExtension(loadedExtensions, config);

        log(LogEvent.lifecycle("Running extension: " + config.getExtensionClass()));
        buildPlan =
            runPluginExtension(extension.getExtraConfigType(), extension, config, buildPlan);
        ImageReference.parse(buildPlan.getBaseImage()); // to validate image reference
      }
      return jibContainerBuilder.applyContainerBuildPlan(buildPlan);

    } catch (InvalidImageReferenceException ex) {
      throw new JibPluginExtensionException(
          Verify.verifyNotNull(extension).getClass(),
          "invalid base image reference: " + buildPlan.getBaseImage(),
          ex);
    }
  }

  // Unchecked casting: "getExtraConfiguration().get()" (Object) to Action<T> and "extension"
  // (JibGradlePluginExtension<?>) to JibGradlePluginExtension<T> where T is the extension-defined
  // config type (as requested by "JibGradlePluginExtension.getExtraConfigType()").
  @SuppressWarnings({"unchecked"})
  private <T> ContainerBuildPlan runPluginExtension(
      Optional<Class<T>> extraConfigType,
      JibGradlePluginExtension<?> extension,
      ExtensionConfiguration config,
      ContainerBuildPlan buildPlan)
      throws JibPluginExtensionException {
    T extraConfig = null;
    Optional<Object> configs = config.getExtraConfiguration();
    if (configs.isPresent()) {
      if (!extraConfigType.isPresent()) {
        throw new IllegalArgumentException(
            "extension "
                + extension.getClass().getSimpleName()
                + " does not expect extension-specific configuration; remove the inapplicable "
                + "'pluginExtension.configuration' from Gradle build script");
      } else {
        // configs.get() is of type Action, so this cast always succeeds.
        // (Note generic <T> is erased at runtime.)
        Action<T> action = (Action<T>) configs.get();
        extraConfig = objects.newInstance(extraConfigType.get());
        action.execute(extraConfig);
      }
    }

    try {
      return ((JibGradlePluginExtension<T>) extension)
          .extendContainerBuildPlan(
              buildPlan,
              config.getProperties(),
              Optional.ofNullable(extraConfig),
              () -> {
                return null;
              },
              new PluginExtensionLogger(this::log));
    } catch (RuntimeException ex) {
      throw new JibPluginExtensionException(
          extension.getClass(), "extension crashed: " + ex.getMessage(), ex);
    }
  }

  private JibGradlePluginExtension<?> findConfiguredExtension(
      List<JibGradlePluginExtension<?>> extensions, ExtensionConfiguration config)
      throws JibPluginExtensionException {
    Predicate<JibGradlePluginExtension<?>> matchesClassName =
        extension -> extension.getClass().getName().equals(config.getExtensionClass());
    Optional<JibGradlePluginExtension<?>> found =
        extensions.stream().filter(matchesClassName).findFirst();
    if (!found.isPresent()) {
      throw new JibPluginExtensionException(
          NullExtension.class,
          "extension configured but not discovered on Jib runtime classpath: "
              + config.getExtensionClass());
    }
    return found.get();
  }
}
