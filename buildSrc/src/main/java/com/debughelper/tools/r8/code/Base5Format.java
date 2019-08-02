// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Instruction;

public abstract class Base5Format extends Instruction {

  public static final int SIZE = 5;

  protected Base5Format() {}

  public Base5Format(BytecodeStream stream) {
    super(stream);
  }

  @Override
  public int getSize() {
    return SIZE;
  }
}