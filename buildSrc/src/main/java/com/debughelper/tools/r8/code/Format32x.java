// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import static com.debughelper.tools.r8.dex.Constants.U16BIT_MAX;

import com.debughelper.tools.r8.code.Base3Format;
import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.naming.ClassNameMapper;

import java.nio.ShortBuffer;

abstract class Format32x extends Base3Format {

  public final int AAAA;
  public final int BBBB;

  // øø | op | AAAA | BBBB
  Format32x(int high, BytecodeStream stream) {
    super(stream);
    AAAA = read16BitValue(stream);
    BBBB = read16BitValue(stream);
  }

  Format32x(int dest, int src) {
    assert 0 <= dest && dest <= U16BIT_MAX;
    assert 0 <= src && src <= U16BIT_MAX;
    AAAA = dest;
    BBBB = src;
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(0, dest);
    write16BitValue(AAAA, dest);
    write16BitValue(BBBB, dest);
  }

  @Override
  public final int hashCode() {
    return ((AAAA << 16) | BBBB) ^ getClass().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format32x o = (Format32x) other;
    return o.AAAA == AAAA && o.BBBB == BBBB;
  }

  @Override
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AAAA + ", v" + BBBB);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AAAA + ", v" + BBBB);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
                                  DexMethod method, int instructionOffset) {
    // No references.
  }
}
