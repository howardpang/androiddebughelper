// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import static com.debughelper.tools.r8.utils.FileUtils.isArchive;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.OneShotByteResource;
import com.debughelper.tools.r8.utils.ZipUtils;
import com.debughelper.tools.r8.DataDirectoryResource;
import com.debughelper.tools.r8.DataEntryResource;
import com.debughelper.tools.r8.DataResourceProvider;
import com.debughelper.tools.r8.ProgramResourceProvider;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.origin.ArchiveEntryOrigin;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.debughelper.tools.r8.shaking.FilteredClassPath;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ArchiveResourceProvider implements ProgramResourceProvider, com.debughelper.tools.r8.DataResourceProvider {

  private final com.debughelper.tools.r8.origin.Origin origin;
  private final com.debughelper.tools.r8.shaking.FilteredClassPath archive;
  private final boolean ignoreDexInArchive;

  ArchiveResourceProvider(FilteredClassPath archive, boolean ignoreDexInArchive) {
    assert isArchive(archive.getPath());
    origin = new PathOrigin(archive.getPath());
    this.archive = archive;
    this.ignoreDexInArchive = ignoreDexInArchive;
  }

  private List<com.debughelper.tools.r8.ProgramResource> readArchive() throws IOException {
    List<com.debughelper.tools.r8.ProgramResource> dexResources = new ArrayList<>();
    List<com.debughelper.tools.r8.ProgramResource> classResources = new ArrayList<>();
    try (ZipFile zipFile = new ZipFile(archive.getPath().toFile(), StandardCharsets.UTF_8)) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        try (InputStream stream = zipFile.getInputStream(entry)) {
          String name = entry.getName();
          Origin entryOrigin = new ArchiveEntryOrigin(name, origin);
          if (archive.matchesFile(name)) {
            if (com.debughelper.tools.r8.utils.ZipUtils.isDexFile(name)) {
              if (!ignoreDexInArchive) {
                com.debughelper.tools.r8.ProgramResource resource =
                    com.debughelper.tools.r8.utils.OneShotByteResource.create(
                        com.debughelper.tools.r8.ProgramResource.Kind.DEX, entryOrigin, ByteStreams.toByteArray(stream), null);
                dexResources.add(resource);
              }
            } else if (com.debughelper.tools.r8.utils.ZipUtils.isClassFile(name)) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              com.debughelper.tools.r8.ProgramResource resource =
                  com.debughelper.tools.r8.utils.OneShotByteResource.create(
                      com.debughelper.tools.r8.ProgramResource.Kind.CF,
                      entryOrigin,
                      ByteStreams.toByteArray(stream),
                      Collections.singleton(descriptor));
              classResources.add(resource);
            }
          }
        }
      }
    } catch (ZipException e) {
      throw new com.debughelper.tools.r8.errors.CompilationError(
          "Zip error while reading '" + archive + "': " + e.getMessage(), e);
    }
    if (!dexResources.isEmpty() && !classResources.isEmpty()) {
      throw new com.debughelper.tools.r8.errors.CompilationError(
          "Cannot create debughelper app from an archive '" + archive
              + "' containing both DEX and Java-bytecode content");
    }
    return !dexResources.isEmpty() ? dexResources : classResources;
  }

  @Override
  public Collection<com.debughelper.tools.r8.ProgramResource> getProgramResources() throws com.debughelper.tools.r8.ResourceException {
    try {
      return readArchive();
    } catch (IOException e) {
      throw new com.debughelper.tools.r8.ResourceException(origin, e);
    }
  }

  @Override
  public DataResourceProvider getDataResourceProvider() {
    return this;
  }

  @Override
  public void accept(Visitor resourceBrowser) throws com.debughelper.tools.r8.ResourceException {
    try (ZipFile zipFile = new ZipFile(archive.getPath().toFile(), StandardCharsets.UTF_8)) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (archive.matchesFile(name) && !isProgramResourceName(name)) {
          if (entry.isDirectory()) {
            resourceBrowser.visit(DataDirectoryResource.fromZip(zipFile, entry));
          } else {
            resourceBrowser.visit(DataEntryResource.fromZip(zipFile, entry));
          }
        }
      }
    } catch (ZipException e) {
      throw new com.debughelper.tools.r8.ResourceException(origin, new com.debughelper.tools.r8.errors.CompilationError(
          "Zip error while reading '" + archive + "': " + e.getMessage(), e));
    } catch (IOException e) {
      throw new ResourceException(origin, new CompilationError(
          "I/O exception while reading '" + archive + "': " + e.getMessage(), e));
    }
  }

  private boolean isProgramResourceName(String name) {
    return com.debughelper.tools.r8.utils.ZipUtils.isClassFile(name) || (ZipUtils.isDexFile(name) && !ignoreDexInArchive);
  }
}
