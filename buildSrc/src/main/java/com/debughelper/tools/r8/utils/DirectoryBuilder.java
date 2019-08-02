// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.utils.ExceptionDiagnostic;
import com.debughelper.tools.r8.utils.FileUtils;
import com.debughelper.tools.r8.utils.OutputBuilder;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.DataEntryResource;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DirectoryBuilder implements OutputBuilder {
  private final Path root;
  private final com.debughelper.tools.r8.origin.Origin origin;

  public DirectoryBuilder(Path root) {
    this.root = root;
    origin = new com.debughelper.tools.r8.origin.PathOrigin(root);
  }

  @Override
  public void open() {
  }

  @Override
  public void close(com.debughelper.tools.r8.DiagnosticsHandler handler) {
  }

  @Override
  public void addDirectory(String name, com.debughelper.tools.r8.DiagnosticsHandler handler) {
    Path target = root.resolve(name.replace(NAME_SEPARATOR, File.separatorChar));
    try {
      Files.createDirectories(target.getParent());
    } catch (IOException e) {
      handler.error(new com.debughelper.tools.r8.utils.ExceptionDiagnostic(e, new com.debughelper.tools.r8.origin.PathOrigin(target)));
    }
  }

  @Override
  public void addFile(String name, DataEntryResource content, com.debughelper.tools.r8.DiagnosticsHandler handler) {
    try (InputStream in = content.getByteStream()) {
      addFile(name, ByteStreams.toByteArray(in), handler);
    } catch (IOException e) {
      handler.error(new com.debughelper.tools.r8.utils.ExceptionDiagnostic(e, content.getOrigin()));
    } catch (ResourceException e) {
      handler.error(new StringDiagnostic("Failed to open input: " + e.getMessage(),
          content.getOrigin()));
    }
  }

  @Override
  public synchronized void addFile(String name, byte[] content, DiagnosticsHandler handler) {
    Path target = root.resolve(name.replace(NAME_SEPARATOR, File.separatorChar));
    try {
      Files.createDirectories(target.getParent());
      FileUtils.writeToFile(target, null, content);
    } catch (IOException e) {
      handler.error(new ExceptionDiagnostic(e, new PathOrigin(target)));
    }
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public Path getPath() {
    return root;
  }
}
