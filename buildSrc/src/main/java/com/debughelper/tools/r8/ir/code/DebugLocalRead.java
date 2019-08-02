// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.utils.InternalOptions;

public class DebugLocalRead extends com.debughelper.tools.r8.ir.code.Instruction {
  private static final String ERROR_MESSAGE = "Unexpected attempt to emit debug-local read.";

  public DebugLocalRead() {
    super(null);
  }

  @Override
  public boolean isDebugLocalRead() {
    return true;
  }

  @Override
  public DebugLocalRead asDebugLocalRead() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isDebugLocalRead();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable();
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable();
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    // Reads are never dead code.
    // They should also have a non-empty set of debug values (see RegAlloc::computeDebugInfo)
    return false;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    // Non-materializing so no stack values are needed.
  }
}
