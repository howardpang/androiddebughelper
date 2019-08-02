// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.code.CfInvoke;
import com.debughelper.tools.r8.code.InvokeVirtualRange;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.Collection;
import java.util.List;
import org.objectweb.asm.Opcodes;

public class InvokeVirtual extends InvokeMethodWithReceiver {

  public InvokeVirtual(DexMethod target, Value result, List<Value> arguments) {
    super(target, result, arguments);
  }

  @Override
  public Invoke.Type getType() {
    return Invoke.Type.VIRTUAL;
  }

  @Override
  protected String getTypeString() {
    return "Virtual";
  }

  @Override
  public DexEncodedMethod computeSingleTarget(
      AppInfoWithLiveness appInfo, TypeEnvironment typeEnvironment, DexType invocationContext) {
    DexType refinedReceiverType = typeEnvironment.getRefinedReceiverType(this);
    return appInfo.lookupSingleVirtualTarget(getInvokedMethod(), refinedReceiverType);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.debughelper.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeVirtualRange(firstRegister, argumentRegisters, getInvokedMethod());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.debughelper.tools.r8.code.InvokeVirtual(
          argumentRegistersCount,
          getInvokedMethod(),
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeVirtual() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeVirtual() {
    return true;
  }

  @Override
  public InvokeVirtual asInvokeVirtual() {
    return this;
  }

  @Override
  public DexEncodedMethod lookupSingleTarget(AppInfoWithLiveness appInfo,
      DexType invocationContext) {
    DexMethod method = getInvokedMethod();
    return appInfo.lookupSingleVirtualTarget(method);
  }

  @Override
  public Collection<DexEncodedMethod> lookupTargets(AppInfoWithSubtyping appInfo,
      DexType invocationContext) {
    return appInfo.lookupVirtualTargets(getInvokedMethod());
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return inliningConstraintForVirtualInvoke(info, invocationContext);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfInvoke(Opcodes.INVOKEVIRTUAL, getInvokedMethod(), false));
  }
}
