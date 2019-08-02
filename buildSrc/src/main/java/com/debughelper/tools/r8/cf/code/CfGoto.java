// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfGoto extends CfInstruction {

  private final com.debughelper.tools.r8.cf.code.CfLabel target;

  public CfGoto(com.debughelper.tools.r8.cf.code.CfLabel target) {
    this.target = target;
  }

  @Override
  public CfLabel getTarget() {
    return target;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitJumpInsn(Opcodes.GOTO, target.getLabel());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    builder.addGoto(code.getLabelOffset(target));
  }
}
