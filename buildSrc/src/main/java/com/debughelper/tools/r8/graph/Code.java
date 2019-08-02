// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.CfCode;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.JarCode;
import com.debughelper.tools.r8.graph.LazyCfCode;
import com.debughelper.tools.r8.ir.optimize.Outliner.OutlineCode;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.ValueNumberGenerator;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.InternalOptions;

public abstract class Code extends CachedHashValueDexItem {

  public abstract com.debughelper.tools.r8.ir.code.IRCode buildIR(
          com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod, com.debughelper.tools.r8.graph.AppInfo appInfo, com.debughelper.tools.r8.utils.InternalOptions options, com.debughelper.tools.r8.origin.Origin origin);

  public IRCode buildInliningIR(
      com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod,
      AppInfo appInfo,
      InternalOptions options,
      ValueNumberGenerator valueNumberGenerator,
      Position callerPosition,
      Origin origin) {
    throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected attempt to build IR graph for inlining from: "
        + getClass().getCanonicalName());
  }

  public abstract void registerCodeReferences(UseRegistry registry);

  @Override
  public abstract String toString();

  public abstract String toString(DexEncodedMethod method, ClassNameMapper naming);

  public boolean isCfCode() {
    return false;
  }

  public boolean isDexCode() {
    return false;
  }

  public boolean isJarCode() {
    return false;
  }

  public boolean isOutlineCode() {
    return false;
  }

  /** Estimate the number of IR instructions emitted by buildIR(). */
  public int estimatedSizeForInlining() {
    return Integer.MAX_VALUE;
  }

  /** Compute estimatedSizeForInlining() <= threshold. */
  public boolean estimatedSizeForInliningAtMost(int threshold) {
    return estimatedSizeForInlining() <= threshold;
  }

  public CfCode asCfCode() {
    throw new com.debughelper.tools.r8.errors.Unreachable(getClass().getCanonicalName() + ".asCfCode()");
  }

  public LazyCfCode asLazyCfCode() {
    throw new com.debughelper.tools.r8.errors.Unreachable(getClass().getCanonicalName() + ".asLazyCfCode()");
  }

  public DexCode asDexCode() {
    throw new com.debughelper.tools.r8.errors.Unreachable(getClass().getCanonicalName() + ".asDexCode()");
  }

  public JarCode asJarCode() {
    throw new com.debughelper.tools.r8.errors.Unreachable(getClass().getCanonicalName() + ".asJarCode()");
  }

  public OutlineCode asOutlineCode() {
    throw new com.debughelper.tools.r8.errors.Unreachable(getClass().getCanonicalName() + ".asOutlineCode()");
  }

  @Override
  void collectIndexedItems(IndexedItemCollection collection,
      DexMethod method, int instructionOffset) {
    throw new com.debughelper.tools.r8.errors.Unreachable();
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection collection) {
    throw new Unreachable();
  }

  public abstract boolean isEmptyVoidMethod();
}
