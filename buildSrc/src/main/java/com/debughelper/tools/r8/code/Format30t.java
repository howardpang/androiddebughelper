// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.Base3Format;
import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.naming.ClassNameMapper;

import java.nio.ShortBuffer;

abstract class Format30t extends Base3Format {

  public /* offset */ int AAAAAAAA;

  // øø | op | AAAAlo | AAAAhi
  Format30t(int high, BytecodeStream stream) {
    super(stream);
    AAAAAAAA = readSigned32BitValue(stream);
  }

  protected Format30t(int AAAAAAAA) {
    this.AAAAAAAA = AAAAAAAA;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(0, dest);
    write32BitValue(AAAAAAAA, dest);
  }

  @Override
  public final int hashCode() {
    return AAAAAAAA ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    return ((Format30t) other).AAAAAAAA == AAAAAAAA;
  }

  @Override
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString(formatOffset(AAAAAAAA));
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(":label_" + (getOffset() + AAAAAAAA));
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
                                  DexMethod method, int instructionOffset) {
    // No references.
  }
}
