// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.ir.code.Monitor.Type;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.CfState.Slot;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.ir.code.Monitor;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfMonitor extends CfInstruction {

  private final Monitor.Type type;

  public CfMonitor(Monitor.Type type) {
    this.type = type;
  }

  public Monitor.Type getType() {
    return type;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(type == Monitor.Type.ENTER ? Opcodes.MONITORENTER : Opcodes.MONITOREXIT);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public boolean canThrow() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    Slot object = state.pop();
    builder.addMonitor(type, object.register);
  }
}
