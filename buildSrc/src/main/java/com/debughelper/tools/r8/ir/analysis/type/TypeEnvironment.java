// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.List;

public interface TypeEnvironment {
  void analyze();
  void analyzeBlocks(List<BasicBlock> blocks);
  void enqueue(com.debughelper.tools.r8.ir.code.Value value);

  TypeLatticeElement getLatticeElement(Value value);
  DexType getRefinedReceiverType(InvokeMethodWithReceiver invoke);
}
