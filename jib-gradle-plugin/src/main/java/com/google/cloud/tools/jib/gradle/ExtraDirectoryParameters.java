/*
 * Copyright 2020 Google LLC.
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

import com.google.cloud.tools.jib.plugins.common.RawConfiguration.ExtraDirectoriesConfiguration;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;

/** Configuration of an extra directory. */
public class ExtraDirectoryParameters implements ExtraDirectoriesConfiguration {

  private final FileOperations fileOperations;
  private final Property<Path> from;
  private final Property<String> into;
  private final ListProperty<String> includes;
  private final ListProperty<String> excludes;

  @Inject
  public ExtraDirectoryParameters(ObjectFactory objects, FileOperations fileOperations) {
    this.fileOperations = fileOperations;
    this.from = objects.property(Path.class).value(Paths.get(""));
    this.into = objects.property(String.class).value("/");
    this.includes = objects.listProperty(String.class);
    this.excludes = objects.listProperty(String.class);
  }

  ExtraDirectoryParameters(
      ObjectFactory objects, FileOperations fileOperations, Path from, String into) {
    this.fileOperations = fileOperations;
    this.from = objects.property(Path.class).value(from);
    this.into = objects.property(String.class).value(into);
    this.includes = objects.listProperty(String.class);
    this.excludes = objects.listProperty(String.class);
  }

  @Input
  public String getFromString() {
    // Gradle warns about @Input annotations on File objects, so we have to expose a getter for a
    // String to make them go away.
    return from.get().toString();
  }

  @Override
  @Internal
  public Path getFrom() {
    return from.get();
  }

  public void setFrom(Object from) {
    this.from.set(fileOperations.file(from).toPath());
  }

  public void setFrom(Provider<Object> from) {
    this.from.set(from.map(obj -> fileOperations.file(obj).toPath()));
  }

  @Override
  @Input
  public String getInto() {
    return into.get();
  }

  public void setInto(String into) {
    this.into.set(into);
  }

  public void setInto(Provider<String> into) {
    this.into.set(into);
  }

  @Input
  public ListProperty<String> getIncludes() {
    return includes;
  }

  @Input
  public ListProperty<String> getExcludes() {
    return excludes;
  }

  @Override
  @Internal
  public List<String> getIncludesList() {
    return includes.get();
  }

  @Override
  @Internal
  public List<String> getExcludesList() {
    return excludes.get();
  }
}
