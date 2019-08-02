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
import com.debughelper.tools.r8.ir.code.Position;

import org.objectweb.asm.MethodVisitor;

public class CfPosition extends CfInstruction {

  private final com.debughelper.tools.r8.cf.code.CfLabel label;
  private final com.debughelper.tools.r8.ir.code.Position position;

  public CfPosition(CfLabel label, com.debughelper.tools.r8.ir.code.Position position) {
    this.label = label;
    this.position = position;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitLineNumber(position.line, label.getLabel());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public Position getPosition() {
    return position;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    state.setPosition(position);
    builder.addDebugPosition(position);
  }
}
