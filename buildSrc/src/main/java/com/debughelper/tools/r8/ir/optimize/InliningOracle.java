// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.optimize.Inliner.InlineAction;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.InvokePolymorphic;
import com.debughelper.tools.r8.ir.code.InvokeStatic;

/**
 * The InliningOracle contains information needed for when inlining other methods into @method.
 */
public interface InliningOracle {

  void finish();

  InlineAction computeForInvokeWithReceiver(
          InvokeMethodWithReceiver invoke, com.debughelper.tools.r8.graph.DexType invocationContext);

  InlineAction computeForInvokeStatic(
          InvokeStatic invoke, com.debughelper.tools.r8.graph.DexType invocationContext);

  InlineAction computeForInvokePolymorphic(
          InvokePolymorphic invoke, DexType invocationContext);
}
