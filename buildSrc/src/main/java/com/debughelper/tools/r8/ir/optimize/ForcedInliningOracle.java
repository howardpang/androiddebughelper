// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.optimize.Inliner;
import com.debughelper.tools.r8.ir.optimize.Inliner.InlineAction;
import com.debughelper.tools.r8.ir.optimize.Inliner.Reason;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.InvokePolymorphic;
import com.debughelper.tools.r8.ir.code.InvokeStatic;

import java.util.ListIterator;
import java.util.Map;

final class ForcedInliningOracle implements InliningOracle, InliningStrategy {
  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, com.debughelper.tools.r8.ir.optimize.Inliner.InliningInfo> invokesToInline;

  ForcedInliningOracle(com.debughelper.tools.r8.graph.DexEncodedMethod method,
                       Map<com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver, com.debughelper.tools.r8.ir.optimize.Inliner.InliningInfo> invokesToInline) {
    this.method = method;
    this.invokesToInline = invokesToInline;
  }

  @Override
  public void finish() {
  }

  @Override
  public InlineAction computeForInvokeWithReceiver(
          InvokeMethodWithReceiver invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    com.debughelper.tools.r8.ir.optimize.Inliner.InliningInfo info = invokesToInline.get(invoke);
    if (info == null) {
      return null;
    }

    assert method != info.target;
    return new InlineAction(info.target, invoke, Reason.FORCE);
  }

  @Override
  public InlineAction computeForInvokeStatic(
          InvokeStatic invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    return null; // Not yet supported.
  }

  @Override
  public InlineAction computeForInvokePolymorphic(
          InvokePolymorphic invoke, com.debughelper.tools.r8.graph.DexType invocationContext) {
    return null; // Not yet supported.
  }

  @Override
  public void ensureMethodProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee) {
    assert target.isProcessed();
  }

  @Override
  public boolean isValidTarget(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee) {
    return true;
  }

  @Override
  public ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> updateTypeInformationIfNeeded(com.debughelper.tools.r8.ir.code.IRCode inlinee,
                                                                                                 ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator, com.debughelper.tools.r8.ir.code.BasicBlock block, BasicBlock invokeSuccessor) {
    return blockIterator;
  }

  @Override
  public boolean exceededAllowance() {
    return false; // Never exceeds allowance.
  }

  @Override
  public void markInlined(IRCode inlinee) {
  }

  @Override
  public DexType getReceiverTypeIfKnown(InvokeMethod invoke) {
    assert invoke.isInvokeMethodWithReceiver();
    Inliner.InliningInfo info = invokesToInline.get(invoke.asInvokeMethodWithReceiver());
    assert info != null;
    return info.receiverType;
  }
}
