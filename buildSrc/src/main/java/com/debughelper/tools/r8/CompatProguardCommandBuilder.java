// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8;

import com.debughelper.tools.r8.Keep;
import com.debughelper.tools.r8.R8Command;

import java.nio.file.Path;

// This class is used by the debughelper Studio Gradle plugin and is thus part of the R8 API.
@Keep
public class CompatProguardCommandBuilder extends R8Command.Builder {
  public CompatProguardCommandBuilder() {
    this(true);
  }

  public CompatProguardCommandBuilder(
      boolean forceProguardCompatibility, DiagnosticsHandler diagnosticsHandler) {
    super(diagnosticsHandler);
    if (forceProguardCompatibility) {
      internalForceProguardCompatibility();
    }
    setIgnoreDexInArchive(true);
  }

  public CompatProguardCommandBuilder(boolean forceProguardCompatibility) {
    if (forceProguardCompatibility) {
      internalForceProguardCompatibility();
    }
    setIgnoreDexInArchive(true);
  }

  public void setProguardCompatibilityRulesOutput(Path path) {
    proguardCompatibilityRulesOutput = path;
  }
}
