// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BaseInstructionFactory;
import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.ConstString;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

public class InstructionFactory extends com.debughelper.tools.r8.code.BaseInstructionFactory {

  private com.debughelper.tools.r8.graph.DexString highestSortingString = null;

  static private com.debughelper.tools.r8.code.Instruction readFrom(ShortBufferBytecodeStream stream,
                                                                com.debughelper.tools.r8.graph.OffsetToObjectMapping mapping) {
    int high = stream.nextByte();
    int opcode = stream.nextByte();
    return create(high, opcode, stream, mapping);
  }

  public com.debughelper.tools.r8.code.Instruction[] readSequenceFrom(ByteBuffer buffer, int startIndex, int length,
                                                                  com.debughelper.tools.r8.graph.OffsetToObjectMapping mapping) {
    return readSequenceFrom(buffer.asShortBuffer(), startIndex, length, mapping);
  }

  public com.debughelper.tools.r8.code.Instruction[] readSequenceFrom(ShortBuffer buffer, int startIndex, int length,
                                                                  OffsetToObjectMapping mapping) {
    ShortBufferBytecodeStream range =
        new ShortBufferBytecodeStream(buffer, startIndex, length);
    List<com.debughelper.tools.r8.code.Instruction> insn = new ArrayList<>(length);
    while (range.hasMore()) {
      com.debughelper.tools.r8.code.Instruction instruction = readFrom(range, mapping);
      if (instruction instanceof com.debughelper.tools.r8.code.ConstString) {
        updateHighestSortingString(((ConstString) instruction).getString());
      } else if (instruction instanceof ConstStringJumbo) {
        updateHighestSortingString(((ConstStringJumbo) instruction).getString());
      }
      insn.add(instruction);
    }
    return insn.toArray(new Instruction[insn.size()]);
  }

  public com.debughelper.tools.r8.graph.DexString getHighestSortingString() {
    return highestSortingString;
  }

  private void updateHighestSortingString(DexString string) {
    if (highestSortingString == null || highestSortingString.slowCompareTo(string) < 0) {
      highestSortingString = string;
    }
  }

  private static class ShortBufferBytecodeStream implements BytecodeStream {

    private final int length;
    private final int startIndex;
    private final ShortBuffer source;

    private int offset = 0;
    private int nextByte;
    private boolean cacheContainsValidByte = false;

    ShortBufferBytecodeStream(ShortBuffer source, int startIndex, int length) {
      this.startIndex = startIndex;
      this.length = length;
      this.source = source;
    }

    @Override
    public int nextShort() {
      assert !cacheContainsValidByte : "Unread byte in cache.";
      assert offset < length;
      int result = source.get(startIndex + offset);
      offset += 1;
      return result;
    }

    @Override
    public int nextByte() {
      if (cacheContainsValidByte) {
        cacheContainsValidByte = false;
        return nextByte;
      } else {
        int next = nextShort();
        nextByte = next & 0xff;
        cacheContainsValidByte = true;
        return (next >> 8) & 0xff;
      }
    }

    @Override
    public boolean hasMore() {
      return length - offset > 0;
    }

    @Override
    public int getOffset() {
      return offset;
    }
  }
}
