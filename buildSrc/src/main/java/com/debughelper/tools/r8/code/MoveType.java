// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.ValueType;

public enum  MoveType {
  SINGLE,
  WIDE,
  OBJECT;

  public static MoveType fromValueType(com.debughelper.tools.r8.ir.code.ValueType type) {
    switch (type) {
      case OBJECT:
        return OBJECT;
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
      case INT_OR_FLOAT_OR_NULL:
        return SINGLE;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        return WIDE;
      default:
        throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected value type: " + type);
    }
  }

  public com.debughelper.tools.r8.ir.code.ValueType toValueType() {
    switch (this) {
      case SINGLE:
        return com.debughelper.tools.r8.ir.code.ValueType.INT_OR_FLOAT;
      case WIDE:
        return com.debughelper.tools.r8.ir.code.ValueType.LONG_OR_DOUBLE;
      case OBJECT:
        return ValueType.OBJECT;
      default:
        throw new Unreachable("Unexpected move type: " + this);
    }
  }

  public int requiredRegisters() {
    return this == WIDE ? 2 : 1;
  }
}
