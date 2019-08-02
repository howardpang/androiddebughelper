// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;

public class OptimizationFeedbackSimple implements OptimizationFeedback {

  @Override
  public void methodReturnsArgument(com.debughelper.tools.r8.graph.DexEncodedMethod method, int argument) {
    // Ignored.
  }

  @Override
  public void methodReturnsConstant(com.debughelper.tools.r8.graph.DexEncodedMethod method, long value) {
    // Ignored.
  }

  @Override
  public void methodNeverReturnsNull(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    // Ignored.
  }

  @Override
  public void methodNeverReturnsNormally(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    // Ignored.
  }

  @Override
  public void markProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod method, Constraint state) {
    // Just as processed, don't provide any inlining constraints.
    method.markProcessed(Constraint.NEVER);
  }

  @Override
  public void markCheckNullReceiverBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark) {
    // Ignored.
  }

  @Override
  public void markTriggerClassInitBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark) {
    // Ignored.
  }

  @Override
  public void setClassInlinerEligibility(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility eligibility) {
    // Ignored.
  }

  @Override
  public void setInitializerEnablingJavaAssertions(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    method.setInitializerEnablingJavaAssertions();
  }
}
