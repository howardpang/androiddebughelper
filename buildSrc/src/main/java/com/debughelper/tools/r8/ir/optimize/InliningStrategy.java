// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InvokeMethod;

import java.util.ListIterator;

interface InliningStrategy {
  boolean exceededAllowance();

  void markInlined(com.debughelper.tools.r8.ir.code.IRCode inlinee);

  void ensureMethodProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee);

  boolean isValidTarget(com.debughelper.tools.r8.ir.code.InvokeMethod invoke, DexEncodedMethod target, com.debughelper.tools.r8.ir.code.IRCode inlinee);

  ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> updateTypeInformationIfNeeded(IRCode inlinee,
                                                                                          ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator, com.debughelper.tools.r8.ir.code.BasicBlock block, BasicBlock invokeSuccessor);

  DexType getReceiverTypeIfKnown(InvokeMethod invoke);
}
