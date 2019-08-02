// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import static com.debughelper.tools.r8.utils.FileUtils.isArchive;
import static com.debughelper.tools.r8.utils.FileUtils.isClassFile;
import static com.debughelper.tools.r8.utils.FileUtils.isDexFile;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.utils.FilteredArchiveClassFileProvider;
import com.debughelper.tools.r8.ArchiveClassFileProvider;
import com.debughelper.tools.r8.ClassFileConsumer;
import com.debughelper.tools.r8.ClassFileResourceProvider;
import com.debughelper.tools.r8.DexFilePerClassFileConsumer;
import com.debughelper.tools.r8.DexIndexedConsumer;
import com.debughelper.tools.r8.DirectoryClassFileProvider;
import com.debughelper.tools.r8.OutputMode;
import com.debughelper.tools.r8.ProgramResourceProvider;
import com.debughelper.tools.r8.Resource;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.StringResource;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.InternalCompilerError;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.debughelper.tools.r8.shaking.FilteredClassPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection of program files needed for processing.
 *
 * <p>This abstraction is the main input and output container for a given application.
 */
public class AndroidApp {

  private final ImmutableList<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders;
  private final ImmutableMap<com.debughelper.tools.r8.Resource, String> programResourcesMainDescriptor;
  private final ImmutableList<com.debughelper.tools.r8.ClassFileResourceProvider> classpathResourceProviders;
  private final ImmutableList<com.debughelper.tools.r8.ClassFileResourceProvider> libraryResourceProviders;

  private final com.debughelper.tools.r8.StringResource proguardMapOutputData;
  private final List<com.debughelper.tools.r8.StringResource> mainDexListResources;
  private final List<String> mainDexClasses;

  // See factory methods and AndroidApp.Builder below.
  private AndroidApp(
      ImmutableList<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders,
      ImmutableMap<com.debughelper.tools.r8.Resource, String> programResourcesMainDescriptor,
      ImmutableList<com.debughelper.tools.r8.ClassFileResourceProvider> classpathResourceProviders,
      ImmutableList<com.debughelper.tools.r8.ClassFileResourceProvider> libraryResourceProviders,
      com.debughelper.tools.r8.StringResource proguardMapOutputData,
      List<com.debughelper.tools.r8.StringResource> mainDexListResources,
      List<String> mainDexClasses) {
    this.programResourceProviders = programResourceProviders;
    this.programResourcesMainDescriptor = programResourcesMainDescriptor;
    this.classpathResourceProviders = classpathResourceProviders;
    this.libraryResourceProviders = libraryResourceProviders;
    this.proguardMapOutputData = proguardMapOutputData;
    this.mainDexListResources = mainDexListResources;
    this.mainDexClasses = mainDexClasses;
  }

  static Reporter defaultReporter() {
    return new Reporter(new DefaultDiagnosticsHandler());
  }

  /**
   * Create a new empty builder.
   */
  public static Builder builder() {
    return builder(defaultReporter());
  }

  /** Create a new empty builder. */
  public static Builder builder(Reporter reporter) {
    return new Builder(reporter);
  }

  /**
   * Create a new builder initialized with the resources from @code{app}.
   */
  public static Builder builder(AndroidApp app) {
    return builder(app, defaultReporter());
  }

  /** Create a new builder initialized with the resources from @code{app}. */
  public static Builder builder(AndroidApp app, Reporter reporter) {
    return new Builder(reporter, app);
  }

  /** Get full collection of all program resources from all program providers. */
  public Collection<com.debughelper.tools.r8.ProgramResource> computeAllProgramResources() throws com.debughelper.tools.r8.ResourceException {
    List<com.debughelper.tools.r8.ProgramResource> resources = new ArrayList<>();
    for (com.debughelper.tools.r8.ProgramResourceProvider provider : programResourceProviders) {
      resources.addAll(provider.getProgramResources());
    }
    return resources;
  }

  // TODO(zerny): Remove this method.
  public List<com.debughelper.tools.r8.ProgramResource> getDexProgramResourcesForTesting() throws IOException {
    try {
      return filter(programResourceProviders, com.debughelper.tools.r8.ProgramResource.Kind.DEX);
    } catch (com.debughelper.tools.r8.ResourceException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new com.debughelper.tools.r8.errors.InternalCompilerError("Unexpected resource error", e);
      }
    }
  }

  // TODO(zerny): Remove this method.
  public List<com.debughelper.tools.r8.ProgramResource> getClassProgramResourcesForTesting() throws IOException {
    try {
      return filter(programResourceProviders, com.debughelper.tools.r8.ProgramResource.Kind.CF);
    } catch (com.debughelper.tools.r8.ResourceException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new InternalCompilerError("Unexpected resource error", e);
      }
    }
  }

  /** Get program resource providers. */
  public List<com.debughelper.tools.r8.ProgramResourceProvider> getProgramResourceProviders() {
    return programResourceProviders;
  }

  /** Get classpath resource providers. */
  public List<com.debughelper.tools.r8.ClassFileResourceProvider> getClasspathResourceProviders() {
    return classpathResourceProviders;
  }

  /** Get library resource providers. */
  public List<com.debughelper.tools.r8.ClassFileResourceProvider> getLibraryResourceProviders() {
    return libraryResourceProviders;
  }

  private List<com.debughelper.tools.r8.ProgramResource> filter(List<com.debughelper.tools.r8.ProgramResourceProvider> providers, com.debughelper.tools.r8.ProgramResource.Kind kind)
      throws com.debughelper.tools.r8.ResourceException {
    List<com.debughelper.tools.r8.ProgramResource> out = new ArrayList<>();
    for (com.debughelper.tools.r8.ProgramResourceProvider provider : providers) {
      for (com.debughelper.tools.r8.ProgramResource code : provider.getProgramResources()) {
        if (code.getKind() == kind) {
          out.add(code);
        }
      }
    }
    return out;
  }

  /**
   * Get the proguard-map associated with an output "app" if it exists.
   *
   * <p>Note: this should never be used as the input to a compilation. See proguards ApplyMapping
   * for such use cases.
   */
  public com.debughelper.tools.r8.StringResource getProguardMapOutputData() {
    return proguardMapOutputData;
  }

  /**
   * True if the main dex list resources exists.
   */
  public boolean hasMainDexList() {
    return !(mainDexListResources.isEmpty() && mainDexClasses.isEmpty());
  }

  /**
   * True if the main dex list resources exists.
   */
  public boolean hasMainDexListResources() {
    return !mainDexListResources.isEmpty();
  }

  /**
   * Get the main dex list resources if any.
   */
  public List<com.debughelper.tools.r8.StringResource> getMainDexListResources() {
    return mainDexListResources;
  }

  /**
   * Get the main dex classes if any.
   */
  public List<String> getMainDexClasses() {
    return mainDexClasses;
  }

  /**
   * Write the dex program resources and proguard resource to @code{output}.
   */
  public void write(Path output, com.debughelper.tools.r8.OutputMode outputMode) throws IOException {
    if (FileUtils.isArchive(output)) {
      writeToZip(output, outputMode);
    } else {
      writeToDirectory(output, outputMode);
    }
  }

  /**
   * Write the dex program resources and proguard resource to @code{directory}.
   */
  public void writeToDirectory(Path directory, com.debughelper.tools.r8.OutputMode outputMode) throws IOException {
    List<com.debughelper.tools.r8.ProgramResource> dexProgramSources = getDexProgramResourcesForTesting();
    try {
      if (outputMode == com.debughelper.tools.r8.OutputMode.DexIndexed) {
        com.debughelper.tools.r8.DexIndexedConsumer.DirectoryConsumer.writeResources(directory, dexProgramSources);
      } else {
        com.debughelper.tools.r8.DexFilePerClassFileConsumer.DirectoryConsumer.writeResources(
            directory, dexProgramSources, programResourcesMainDescriptor);
      }
    } catch (com.debughelper.tools.r8.ResourceException e) {
      throw new IOException("Resource Error", e);
    }
  }

  /**
   * Write the dex program resources to @code{archive} and the proguard resource as its sibling.
   */
  public void writeToZip(Path archive, com.debughelper.tools.r8.OutputMode outputMode) throws IOException {
    try {
      if (outputMode == com.debughelper.tools.r8.OutputMode.DexIndexed) {
        List<com.debughelper.tools.r8.ProgramResource> resources = getDexProgramResourcesForTesting();
        DexIndexedConsumer.ArchiveConsumer.writeResources(archive, resources);
      } else if (outputMode == com.debughelper.tools.r8.OutputMode.DexFilePerClassFile) {
        List<com.debughelper.tools.r8.ProgramResource> resources = getDexProgramResourcesForTesting();
        DexFilePerClassFileConsumer.ArchiveConsumer.writeResources(
            archive, resources, programResourcesMainDescriptor);
      } else if (outputMode == OutputMode.ClassFile) {
        List<com.debughelper.tools.r8.ProgramResource> resources = getClassProgramResourcesForTesting();
        ClassFileConsumer.ArchiveConsumer.writeResources(archive, resources);
      } else {
        throw new Unreachable("Unsupported output-mode for writing: " + outputMode);
      }
    } catch (com.debughelper.tools.r8.ResourceException e) {
      throw new IOException("Resource Error", e);
    }
  }

  // Public for testing.
  public String getPrimaryClassDescriptor(Resource resource) {
    assert resource instanceof com.debughelper.tools.r8.ProgramResource;
    return programResourcesMainDescriptor.get(resource);
  }

  /**
   * Builder interface for constructing an AndroidApp.
   */
  public static class Builder {

    private final List<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders = new ArrayList<>();
    private final List<com.debughelper.tools.r8.ProgramResource> programResources = new ArrayList<>();
    private final Map<com.debughelper.tools.r8.ProgramResource, String> programResourcesMainDescriptor = new HashMap<>();
    private final List<com.debughelper.tools.r8.ClassFileResourceProvider> classpathResourceProviders = new ArrayList<>();
    private final List<com.debughelper.tools.r8.ClassFileResourceProvider> libraryResourceProviders = new ArrayList<>();
    private List<com.debughelper.tools.r8.StringResource> mainDexListResources = new ArrayList<>();
    private List<String> mainDexListClasses = new ArrayList<>();
    private boolean ignoreDexInArchive = false;

    // Proguard map data is output only data. This should never be used as input to a compilation.
    private com.debughelper.tools.r8.StringResource proguardMapOutputData;

    private final Reporter reporter;

    // See AndroidApp::builder().
    private Builder(Reporter reporter) {
      this.reporter = reporter;
    }

    // See AndroidApp::builder(AndroidApp).
    private Builder(Reporter reporter, AndroidApp app) {
      this(reporter);
      programResourceProviders.addAll(app.programResourceProviders);
      classpathResourceProviders.addAll(app.classpathResourceProviders);
      libraryResourceProviders.addAll(app.libraryResourceProviders);
      mainDexListResources = app.mainDexListResources;
      mainDexListClasses = app.mainDexClasses;
    }

    public Reporter getReporter() {
      return reporter;
    }

    /** Add program file resources. */
    public Builder addProgramFiles(Path... files) throws NoSuchFileException {
      return addProgramFiles(Arrays.asList(files));
    }

    /** Add program file resources. */
    public Builder addProgramFiles(Collection<Path> files) throws NoSuchFileException {
      for (Path file : files) {
        addProgramFile(file);
      }
      return this;
    }

    /** Add filtered archives of program resources. */
    public Builder addFilteredProgramArchives(Collection<com.debughelper.tools.r8.shaking.FilteredClassPath> filteredArchives) {
      for (com.debughelper.tools.r8.shaking.FilteredClassPath archive : filteredArchives) {
        assert FileUtils.isArchive(archive.getPath());
        ArchiveResourceProvider archiveResourceProvider =
            new ArchiveResourceProvider(archive, ignoreDexInArchive);
        addProgramResourceProvider(archiveResourceProvider);
      }
      return this;
    }

    public Builder addProgramResourceProvider(com.debughelper.tools.r8.ProgramResourceProvider provider) {
      assert provider != null;
      programResourceProviders.add(provider);
      return this;
    }

    /**
     * Add classpath file resources.
     */
    public Builder addClasspathFiles(Path... files) throws IOException {
      return addClasspathFiles(Arrays.asList(files));
    }

    /**
     * Add classpath file resources.
     */
    public Builder addClasspathFiles(Collection<Path> files) throws IOException {
      for (Path file : files) {
        addClasspathFile(file);
      }
      return this;
    }

    /**
     * Add classpath file resources.
     */
    public Builder addClasspathFile(Path file) throws IOException {
      addClasspathOrLibraryProvider(file, classpathResourceProviders);
      return this;
    }

    /**
     * Add classpath resource provider.
     */
    public Builder addClasspathResourceProvider(com.debughelper.tools.r8.ClassFileResourceProvider provider) {
      classpathResourceProviders.add(provider);
      return this;
    }

    /** Add library file resources. */
    public Builder addLibraryFiles(Path... files) throws IOException {
      return addLibraryFiles(Arrays.asList(files));
    }

    /** Add library file resources. */
    public Builder addLibraryFiles(Collection<Path> files) throws IOException {
      for (Path file : files) {
        addClasspathOrLibraryProvider(file, libraryResourceProviders);
      }
      return this;
    }

    /** Add library file resource. */
    public Builder addLibraryFile(Path file) throws IOException {
      addClasspathOrLibraryProvider(file, libraryResourceProviders);
      return this;
    }

    /** Add library file resources. */
    public Builder addFilteredLibraryArchives(Collection<com.debughelper.tools.r8.shaking.FilteredClassPath> filteredArchives) {
      for (com.debughelper.tools.r8.shaking.FilteredClassPath archive : filteredArchives) {
        assert FileUtils.isArchive(archive.getPath());
        try {
          libraryResourceProviders.add(new com.debughelper.tools.r8.utils.FilteredArchiveClassFileProvider(archive));
        } catch (IOException e) {
          reporter.error(new ExceptionDiagnostic(e, new com.debughelper.tools.r8.origin.PathOrigin(archive.getPath())));
        }
      }
      return this;
    }

    /**
     * Add library resource provider.
     */
    public Builder addLibraryResourceProvider(com.debughelper.tools.r8.ClassFileResourceProvider provider) {
      libraryResourceProviders.add(provider);
      return this;
    }

    /**
     * Add dex program-data with class descriptor.
     */
    public Builder addDexProgramData(byte[] data, Set<String> classDescriptors) {
      addProgramResources(
          com.debughelper.tools.r8.ProgramResource.fromBytes(com.debughelper.tools.r8.origin.Origin.unknown(), com.debughelper.tools.r8.ProgramResource.Kind.DEX, data, classDescriptors));
      return this;
    }

    /**
     * Add dex program-data with class descriptor and primary class.
     */
    public Builder addDexProgramData(
        byte[] data,
        Set<String> classDescriptors,
        String primaryClassDescriptor) {
      com.debughelper.tools.r8.ProgramResource resource = com.debughelper.tools.r8.ProgramResource.fromBytes(
          com.debughelper.tools.r8.origin.Origin.unknown(), com.debughelper.tools.r8.ProgramResource.Kind.DEX, data, classDescriptors);
      programResources.add(resource);
      programResourcesMainDescriptor.put(resource, primaryClassDescriptor);
      return this;
    }

    /**
     * Add dex program-data.
     */
    public Builder addDexProgramData(byte[] data, com.debughelper.tools.r8.origin.Origin origin) {
      addProgramResources(com.debughelper.tools.r8.ProgramResource.fromBytes(origin, com.debughelper.tools.r8.ProgramResource.Kind.DEX, data, null));
      return this;
    }

    /**
     * Add dex program-data.
     */
    public Builder addDexProgramData(Collection<byte[]> data) {
      for (byte[] datum : data) {
        addProgramResources(com.debughelper.tools.r8.ProgramResource.fromBytes(com.debughelper.tools.r8.origin.Origin.unknown(), com.debughelper.tools.r8.ProgramResource.Kind.DEX, datum, null));
      }
      return this;
    }

    /**
     * Add Java-bytecode program data.
     */
    public Builder addClassProgramData(Collection<byte[]> data) {
      for (byte[] datum : data) {
        addClassProgramData(datum, com.debughelper.tools.r8.origin.Origin.unknown());
      }
      return this;
    }

    /**
     * Add Java-bytecode program data.
     */
    public Builder addClassProgramData(byte[] data, com.debughelper.tools.r8.origin.Origin origin) {
      return addClassProgramData(data, origin, null);
    }

    public Builder addClassProgramData(byte[] data, com.debughelper.tools.r8.origin.Origin origin, Set<String> classDescriptors) {
      addProgramResources(com.debughelper.tools.r8.ProgramResource.fromBytes(origin, com.debughelper.tools.r8.ProgramResource.Kind.CF, data, classDescriptors));
      return this;
    }

    /**
     * Set proguard-map output data.
     *
     * <p>Note: this should not be used as inputs to compilation!
     */
    public Builder setProguardMapOutputData(String content) {
      proguardMapOutputData =
          content == null ? null : com.debughelper.tools.r8.StringResource.fromString(content, Origin.unknown());
      return this;
    }

    /**
     * Add a main-dex list file.
     */
    public Builder addMainDexListFiles(Path... files) throws NoSuchFileException {
      return addMainDexListFiles(Arrays.asList(files));
    }

    public Builder addMainDexListFiles(Collection<Path> files) throws NoSuchFileException {
      for (Path file : files) {
        if (!Files.exists(file)) {
          throw new NoSuchFileException(file.toString());
        }
        // TODO(sgjesse): Should we just read the file here? This will sacrifice the parallelism
        // in ApplicationReader where all input resources are read in parallel.
        mainDexListResources.add(StringResource.fromFile(file));
      }
      return this;
    }

    /**
     * Add main-dex classes.
     */
    public Builder addMainDexClasses(String... classes) {
      return addMainDexClasses(Arrays.asList(classes));
    }

    /**
     * Add main-dex classes.
     */
    public Builder addMainDexClasses(Collection<String> classes) {
      mainDexListClasses.addAll(classes);
      return this;
    }

    public boolean hasMainDexList() {
      return !(mainDexListResources.isEmpty() && mainDexListClasses.isEmpty());
    }

    /**
     * Ignore dex resources in input archives.
     *
     * In some situations (e.g. AOSP framework build) the input archives include both class and
     * dex resources. Setting this flag ignores the dex resources and reads the class resources
     * only.
     */
    public Builder setIgnoreDexInArchive(boolean value) {
      ignoreDexInArchive = value;
      return this;
    }

    /**
     * Build final AndroidApp.
     */
    public AndroidApp build() {
      if (!programResources.isEmpty()) {
        // If there are individual program resources move them to a dedicated provider.
        final List<com.debughelper.tools.r8.ProgramResource> resources = ImmutableList.copyOf(programResources);
        programResourceProviders.add(
            new com.debughelper.tools.r8.ProgramResourceProvider() {
              @Override
              public Collection<com.debughelper.tools.r8.ProgramResource> getProgramResources() throws ResourceException {
                return resources;
              }
            });
        programResources.clear();
      }
      return new AndroidApp(
          ImmutableList.copyOf(programResourceProviders),
          ImmutableMap.copyOf(programResourcesMainDescriptor),
          ImmutableList.copyOf(classpathResourceProviders),
          ImmutableList.copyOf(libraryResourceProviders),
          proguardMapOutputData,
          mainDexListResources,
          mainDexListClasses);
    }

    public void addProgramFile(Path file) throws NoSuchFileException {
      if (!Files.exists(file)) {
        throw new NoSuchFileException(file.toString());
      }
      if (FileUtils.isDexFile(file)) {
        addProgramResources(com.debughelper.tools.r8.ProgramResource.fromFile(com.debughelper.tools.r8.ProgramResource.Kind.DEX, file));
      } else if (FileUtils.isClassFile(file)) {
        addProgramResources(com.debughelper.tools.r8.ProgramResource.fromFile(com.debughelper.tools.r8.ProgramResource.Kind.CF, file));
      } else if (FileUtils.isArchive(file)) {
        ArchiveResourceProvider archiveResourceProvider = new ArchiveResourceProvider(
            FilteredClassPath.unfiltered(file), ignoreDexInArchive);
        addProgramResourceProvider(archiveResourceProvider);
      } else {
        throw new com.debughelper.tools.r8.errors.CompilationError("Unsupported source file type", new com.debughelper.tools.r8.origin.PathOrigin(file));
      }
    }

    private void addProgramResources(com.debughelper.tools.r8.ProgramResource... resources) {
      addProgramResources(Arrays.asList(resources));
    }

    private void addProgramResources(Collection<com.debughelper.tools.r8.ProgramResource> resources) {
      programResources.addAll(resources);
    }

    private void addClasspathOrLibraryProvider(
        Path file, List<ClassFileResourceProvider> providerList) throws IOException {
      if (!Files.exists(file)) {
        throw new NoSuchFileException(file.toString());
      }
      if (FileUtils.isArchive(file)) {
        providerList.add(new ArchiveClassFileProvider(file));
      } else if (Files.isDirectory(file) ) {
        providerList.add(DirectoryClassFileProvider.fromDirectory(file));
      } else {
        throw new CompilationError("Unsupported source file type", new PathOrigin(file));
      }
    }

    public List<ProgramResourceProvider> getProgramResourceProviders() {
      return programResourceProviders;
    }
  }
}
