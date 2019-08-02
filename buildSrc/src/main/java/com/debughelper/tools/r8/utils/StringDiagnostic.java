// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.Diagnostic;
import com.debughelper.tools.r8.Keep;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.position.Position;

@Keep
public class StringDiagnostic implements Diagnostic {

  private final com.debughelper.tools.r8.origin.Origin origin;
  private final com.debughelper.tools.r8.position.Position position;
  private final String message;

  public StringDiagnostic(String message) {
    this(message, com.debughelper.tools.r8.origin.Origin.unknown());
  }

  public StringDiagnostic(String message, com.debughelper.tools.r8.origin.Origin origin) {
    this(message, origin, com.debughelper.tools.r8.position.Position.UNKNOWN);
  }

  public StringDiagnostic(String message, com.debughelper.tools.r8.origin.Origin origin, com.debughelper.tools.r8.position.Position position) {
    this.origin = origin;
    this.position = position;
    this.message = message;
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public Position getPosition() {
    return position;
  }

  @Override
  public String getDiagnosticMessage() {
    return message;
  }
}
