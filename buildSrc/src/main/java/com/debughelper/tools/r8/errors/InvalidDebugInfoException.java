// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.errors;

import com.debughelper.tools.r8.errors.InternalCompilerError;

public class InvalidDebugInfoException extends InternalCompilerError {
  public InvalidDebugInfoException(String message) {
    super(message);
  }
}
