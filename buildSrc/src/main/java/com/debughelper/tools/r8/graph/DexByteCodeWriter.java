// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.utils.DescriptorUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ThrowingFunction;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

public abstract class DexByteCodeWriter {

  final com.debughelper.tools.r8.graph.DexApplication application;
  final com.debughelper.tools.r8.utils.InternalOptions options;

  DexByteCodeWriter(DexApplication application,
                    InternalOptions options) {
    this.application = application;
    this.options = options;
  }

  private void ensureParentExists(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  private com.debughelper.tools.r8.utils.ThrowingFunction<DexClass, PrintStream, IOException> oneFilePerClass(Path path) {
    return (clazz) -> {
      String className = DescriptorUtils.descriptorToJavaType(clazz.type.toDescriptorString(),
          application.getProguardMap());
      Path classOutput = path.resolve(className.replace('.', File.separatorChar)
          + getFileEnding());
      ensureParentExists(classOutput);
      return new PrintStream(Files.newOutputStream(classOutput));
    };
  }

  public void write(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      write(oneFilePerClass(path), PrintStream::close);
    } else {
      ensureParentExists(path);
      try (PrintStream ps = new PrintStream(Files.newOutputStream(path))) {
        write(ps);
      }
    }
  }

  public void write(PrintStream output) throws IOException {
    write(x -> output, x -> {
    });
  }

  private void write(ThrowingFunction<DexClass, PrintStream, IOException> outputStreamProvider,
                     Consumer<PrintStream> closer)
      throws IOException {
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : application.classes()) {
      if (anyMethodMatches(clazz)) {
        PrintStream ps = outputStreamProvider.apply(clazz);
        try {
          writeClass(clazz, outputStreamProvider.apply(clazz));
        } finally {
          closer.accept(ps);
        }
      }
    }
  }

  private boolean anyMethodMatches(DexClass clazz) {
    return !options.hasMethodsFilter()
        || Arrays.stream(clazz.virtualMethods()).anyMatch(options::methodMatchesFilter)
        || Arrays.stream(clazz.directMethods()).anyMatch(options::methodMatchesFilter);
  }

  private void writeClass(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    writeClassHeader(clazz, ps);
    writeFieldsHeader(clazz, ps);
    clazz.forEachField(field -> writeField(field, ps));
    writeFieldsFooter(clazz, ps);
    writeMethodsHeader(clazz, ps);
    clazz.forEachMethod(method -> writeMethod(method, ps));
    writeMethodsFooter(clazz, ps);
    writeClassFooter(clazz, ps);
  }

  abstract String getFileEnding();

  abstract void writeClassHeader(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps);

  void writeFieldsHeader(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    // Do nothing.
  }

  abstract void writeField(DexEncodedField field, PrintStream ps);

  void writeFieldsFooter(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    // Do nothing.
  }

  void writeMethodsHeader(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    // Do nothing.
  }

  abstract void writeMethod(DexEncodedMethod method, PrintStream ps);

  void writeMethodsFooter(com.debughelper.tools.r8.graph.DexProgramClass clazz, PrintStream ps) {
    // Do nothing.
  }

  abstract void writeClassFooter(DexProgramClass clazz, PrintStream ps);
}
