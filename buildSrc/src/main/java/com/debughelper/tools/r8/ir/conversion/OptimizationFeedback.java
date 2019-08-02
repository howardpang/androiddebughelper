// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;

public interface OptimizationFeedback {
  void methodReturnsArgument(com.debughelper.tools.r8.graph.DexEncodedMethod method, int argument);
  void methodReturnsConstant(com.debughelper.tools.r8.graph.DexEncodedMethod method, long value);
  void methodNeverReturnsNull(com.debughelper.tools.r8.graph.DexEncodedMethod method);
  void methodNeverReturnsNormally(com.debughelper.tools.r8.graph.DexEncodedMethod method);
  void markProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod method, Constraint state);
  void markCheckNullReceiverBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark);
  void markTriggerClassInitBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark);
  void setClassInlinerEligibility(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility eligibility);
  void setInitializerEnablingJavaAssertions(com.debughelper.tools.r8.graph.DexEncodedMethod method);
}
