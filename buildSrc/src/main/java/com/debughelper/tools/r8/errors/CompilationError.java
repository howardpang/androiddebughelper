// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.errors;

import com.debughelper.tools.r8.Diagnostic;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.position.Position;

/**
 * Exception to signal an compilation error.
 * <p>
 * This is always an expected error and considered a user input issue. A user-understandable message
 * must be provided.
 */
public class CompilationError extends RuntimeException implements Diagnostic {

  private final com.debughelper.tools.r8.origin.Origin origin;
  private final com.debughelper.tools.r8.position.Position position;
  public CompilationError(String message) {
    this(message, com.debughelper.tools.r8.origin.Origin.unknown());
  }

  public CompilationError(String message, Throwable cause) {
    this(message, cause, com.debughelper.tools.r8.origin.Origin.unknown());
  }

  public CompilationError(String message, com.debughelper.tools.r8.origin.Origin origin) {
    this(message, null, origin);
  }

  public CompilationError(String message, Throwable cause, com.debughelper.tools.r8.origin.Origin origin) {
    this(message, cause, origin, com.debughelper.tools.r8.position.Position.UNKNOWN);
  }

  public CompilationError(String message, Throwable cause, com.debughelper.tools.r8.origin.Origin origin, com.debughelper.tools.r8.position.Position position) {
    super(message, cause);
    this.origin = origin;
    this.position = position;
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
    return getMessage();
  }
}