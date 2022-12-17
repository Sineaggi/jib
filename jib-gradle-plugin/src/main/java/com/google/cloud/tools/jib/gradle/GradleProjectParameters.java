package com.google.cloud.tools.jib.gradle;

import javax.inject.Inject;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;

public class GradleProjectParameters {
  private final String name;
  private final Property<String> version;
  private final Property<JavaVersion> targetCompatibility;
  private final Property<String> mainClassFromJarPlugin;
  private final ConfigurableFileCollection projectDependencies;
  private final RegularFileProperty warFilePath;
  private final Property<Boolean> isWarProject;
  private final RegularFileProperty jarPath;
  private final ConfigurableFileCollection classesOutputDirectories;
  private final DirectoryProperty resourcesOutputDirectory;
  private final ConfigurableFileCollection allFiles;
  private final boolean offline;
  private final ConsoleOutput consoleOutput;

  @Inject
  public GradleProjectParameters(ObjectFactory objects, Project project) {
    this.name = project.getName();
    this.version = objects.property(String.class);
    this.targetCompatibility = objects.property(JavaVersion.class);
    this.mainClassFromJarPlugin = objects.property(String.class);
    this.projectDependencies = objects.fileCollection();
    this.warFilePath = objects.fileProperty();
    this.isWarProject = objects.property(Boolean.class);
    this.jarPath = objects.fileProperty();
    this.classesOutputDirectories = objects.fileCollection();
    this.resourcesOutputDirectory = objects.directoryProperty();
    this.allFiles = objects.fileCollection();
    this.offline = project.getGradle().getStartParameter().isOffline();
    this.consoleOutput = project.getGradle().getStartParameter().getConsoleOutput();
  }

  /**
   * Project name
   *
   * @return project name
   */
  @Input
  public String getName() {
    return name;
  }

  /**
   * Project version
   *
   * @return project version
   */
  @Input
  public Property<String> getVersion() {
    return version;
  }

  @Input
  public Property<JavaVersion> getTargetCompatibility() {
    return targetCompatibility;
  }

  @Input
  @Optional
  public Property<String> getMainClassFromJarPlugin() {
    return mainClassFromJarPlugin;
  }

  @InputFiles
  public ConfigurableFileCollection getProjectDependencies() {
    return projectDependencies;
  }

  @InputFile
  @Optional
  public RegularFileProperty getWarFilePath() {
    return warFilePath;
  }

  @Input
  public Property<Boolean> getIsWarProject() {
    return isWarProject;
  }

  @InputFile
  public RegularFileProperty getJarPath() {
    return jarPath;
  }

  @InputFiles
  @Optional
  public ConfigurableFileCollection getClassesOutputDirectories() {
    return classesOutputDirectories;
  }

  @InputFile
  @Optional
  public DirectoryProperty getResourcesOutputDirectory() {
    return resourcesOutputDirectory;
  }

  /**
   * This represents all files from the configuration
   *
   * @return the project files
   */
  @InputFiles
  public ConfigurableFileCollection getAllFiles() {
    return allFiles;
  }

  /**
   * Equal to project.getGradle().getStartParameter().isOffline()
   *
   * @return is offline
   */
  @Input
  public boolean isOffline() {
    return offline;
  }

  /**
   * Equal to project.getGradle().getStartParameter().getConsoleOutput()
   *
   * @return console output
   */
  @Input
  public ConsoleOutput getConsoleOutput() {
    return consoleOutput;
  }
}
