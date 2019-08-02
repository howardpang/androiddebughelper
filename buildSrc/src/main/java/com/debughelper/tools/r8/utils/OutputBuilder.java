// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.DataEntryResource;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.origin.Origin;

import java.nio.file.Path;

public interface OutputBuilder {
  char NAME_SEPARATOR = '/';

  void open();

  void close(com.debughelper.tools.r8.DiagnosticsHandler handler);

  void addDirectory(String name, com.debughelper.tools.r8.DiagnosticsHandler handler);

  void addFile(String name, DataEntryResource content, com.debughelper.tools.r8.DiagnosticsHandler handler);

  void addFile(String name, byte[] content, DiagnosticsHandler handler);

  Path getPath();

  Origin getOrigin();
}
