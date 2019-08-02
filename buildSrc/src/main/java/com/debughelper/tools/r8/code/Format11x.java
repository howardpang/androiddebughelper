// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.Base1Format;
import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.naming.ClassNameMapper;

import java.nio.ShortBuffer;

abstract class Format11x extends Base1Format {

  public final short AA;

  // vAA | op
  Format11x(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
  }

  protected Format11x(int AA) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    this.AA = (short) AA;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(AA, dest);
  }

  @Override
  public final int hashCode() {
    return AA ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    return ((Format11x) other).AA == AA;
  }

  @Override
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AA);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
                                  DexMethod method, int instructionOffset) {
    // No references.
  }
}
