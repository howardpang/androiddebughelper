// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.utils.AbortException;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.BaseCompilerCommand;
import com.debughelper.tools.r8.CompilationFailedException;
import com.debughelper.tools.r8.D8Command;
import com.debughelper.tools.r8.Diagnostic;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.R8Command;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.MainDexOverflow;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.position.Position;

import java.util.ArrayList;
import java.util.Collection;

public class Reporter implements com.debughelper.tools.r8.DiagnosticsHandler {

  private final com.debughelper.tools.r8.DiagnosticsHandler clientHandler;
  private final com.debughelper.tools.r8.BaseCompilerCommand command;
  private int errorCount = 0;
  private com.debughelper.tools.r8.Diagnostic lastError;
  private final Collection<Throwable> suppressedExceptions = new ArrayList<>();

  public Reporter(com.debughelper.tools.r8.DiagnosticsHandler clientHandler) {
    this(clientHandler, null);
  }

  public Reporter(DiagnosticsHandler clientHandler, BaseCompilerCommand command) {
    this.clientHandler = clientHandler;
    this.command = command;
  }

  @Override
  public void info(com.debughelper.tools.r8.Diagnostic info) {
    clientHandler.info(info);
  }

  @Override
  public void warning(com.debughelper.tools.r8.Diagnostic warning) {
    clientHandler.warning(warning);
  }

  @Override
  public void error(com.debughelper.tools.r8.Diagnostic error) {
    clientHandler.error(error);
    synchronized (this) {
      lastError = error;
      errorCount++;
    }
  }

  public void error(String message) {
    error(new com.debughelper.tools.r8.utils.StringDiagnostic(message));
  }

  public void error(com.debughelper.tools.r8.Diagnostic error, Throwable suppressedException) {
    clientHandler.error(error);
    synchronized (this) {
      lastError = error;
      errorCount++;
      suppressedExceptions.add(suppressedException);
    }
  }

  /**
   * @throws com.debughelper.tools.r8.utils.AbortException always.
   */
  public RuntimeException fatalError(com.debughelper.tools.r8.Diagnostic error) {
    error(error);
    failIfPendingErrors();
    throw new com.debughelper.tools.r8.errors.Unreachable();
  }

  /**
   * @throws com.debughelper.tools.r8.utils.AbortException always.
   */
  public RuntimeException fatalError(Diagnostic error, Throwable suppressedException) {
    error(error, suppressedException);
    failIfPendingErrors();
    throw new Unreachable();
  }

  /**
   * @throws com.debughelper.tools.r8.utils.AbortException always.
   */
  public RuntimeException fatalError(MainDexOverflow e) {
    if (command instanceof R8Command) {
      return fatalError(new com.debughelper.tools.r8.utils.StringDiagnostic(e.getMessageForR8()));
    } else if (command instanceof D8Command) {
      return fatalError(new com.debughelper.tools.r8.utils.StringDiagnostic(e.getMessageForD8()));
    } else {
      return fatalError(new StringDiagnostic(e.getMessage()));
    }
  }

  /**
   * @throws com.debughelper.tools.r8.utils.AbortException if any error was reported.
   */
  public void failIfPendingErrors() {
    synchronized (this) {
      if (errorCount != 0) {
        com.debughelper.tools.r8.utils.AbortException abort;
        if (lastError != null && lastError.getDiagnosticMessage() != null) {
          StringBuilder builder = new StringBuilder("Error: ");
          if (lastError.getOrigin() != Origin.unknown()) {
            builder.append(lastError.getOrigin()).append(", ");
          }
          if (lastError.getPosition() != Position.UNKNOWN) {
            builder.append(lastError.getPosition()).append(", ");
          }
          builder.append(lastError.getDiagnosticMessage());
          abort = new com.debughelper.tools.r8.utils.AbortException(builder.toString());
        } else {
          abort = new com.debughelper.tools.r8.utils.AbortException();
        }
        throw addSuppressedExceptions(abort);
      }
    }
  }

  private <T extends Throwable> T addSuppressedExceptions(T t) {
    suppressedExceptions.forEach(throwable -> t.addSuppressed(throwable));
    return t;
  }

  public void guard(Runnable action) throws com.debughelper.tools.r8.CompilationFailedException {
    try {
      action.run();
    } catch (CompilationError e) {
      error(e);
      throw addSuppressedExceptions(new com.debughelper.tools.r8.CompilationFailedException());
    } catch (AbortException e) {
      throw new CompilationFailedException(e);
    }
  }
}
