// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.regalloc;

import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.utils.InternalOptions;

public interface RegisterAllocator {
  void allocateRegisters(boolean debug);
  int registersUsed();
  int getRegisterForValue(com.debughelper.tools.r8.ir.code.Value value, int instructionNumber);
  int getArgumentOrAllocateRegisterForValue(Value value, int instructionNumber);
  InternalOptions getOptions();
}
