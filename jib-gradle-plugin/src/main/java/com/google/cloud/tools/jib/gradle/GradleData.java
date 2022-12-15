package com.google.cloud.tools.jib.gradle;

import javax.inject.Inject;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;

public class GradleData {
  private final Property<String> name;
  private final Property<String> version;
  private final Property<JavaVersion> targetCompatibility;
  private final Property<String> mainClassFromJarPlugin;
  private final ConfigurableFileCollection projectDependencies;
  private final RegularFileProperty warFilePath;
  private final Property<Boolean> isWarProject;
  private final RegularFileProperty jarPath;

  @Inject
  public GradleData(ObjectFactory objects) {
    this.name = objects.property(String.class);
    this.version = objects.property(String.class);
    this.targetCompatibility = objects.property(JavaVersion.class);
    this.mainClassFromJarPlugin = objects.property(String.class);
    this.projectDependencies = objects.fileCollection();
    this.warFilePath = objects.fileProperty();
    this.isWarProject = objects.property(Boolean.class);
    this.jarPath = objects.fileProperty();
  }

  @Input
  public Property<String> getName() {
    return name;
  }

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
}
