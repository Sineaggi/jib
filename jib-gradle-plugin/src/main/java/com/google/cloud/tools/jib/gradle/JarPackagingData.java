package com.google.cloud.tools.jib.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

public abstract class JarPackagingData extends PackagingData {
  @Input
  @Optional
  public abstract RegularFileProperty getJar();
}
