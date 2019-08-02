// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import static com.debughelper.tools.r8.graph.DexCode.TryHandler.NO_HANDLER;

import com.debughelper.tools.r8.code.Format21t;
import com.debughelper.tools.r8.graph.DexCode;
import com.debughelper.tools.r8.graph.DexCode.Try;
import com.debughelper.tools.r8.graph.DexCode.TryHandler;
import com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.debughelper.tools.r8.graph.DexDebugEvent;
import com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC;
import com.debughelper.tools.r8.graph.DexDebugEvent.Default;
import com.debughelper.tools.r8.code.ConstString;
import com.debughelper.tools.r8.code.ConstStringJumbo;
import com.debughelper.tools.r8.code.Format22t;
import com.debughelper.tools.r8.code.Format31t;
import com.debughelper.tools.r8.code.Goto;
import com.debughelper.tools.r8.code.Goto16;
import com.debughelper.tools.r8.code.Goto32;
import com.debughelper.tools.r8.code.IfEq;
import com.debughelper.tools.r8.code.IfEqz;
import com.debughelper.tools.r8.code.IfGe;
import com.debughelper.tools.r8.code.IfGez;
import com.debughelper.tools.r8.code.IfGt;
import com.debughelper.tools.r8.code.IfGtz;
import com.debughelper.tools.r8.code.IfLe;
import com.debughelper.tools.r8.code.IfLez;
import com.debughelper.tools.r8.code.IfLt;
import com.debughelper.tools.r8.code.IfLtz;
import com.debughelper.tools.r8.code.IfNe;
import com.debughelper.tools.r8.code.IfNez;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.code.Nop;
import com.debughelper.tools.r8.code.SwitchPayload;
import com.debughelper.tools.r8.graph.DexDebugInfo;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.ir.code.If;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

public class JumboStringRewriter {

  private static class TryTargets {
    private com.debughelper.tools.r8.code.Instruction start;
    private com.debughelper.tools.r8.code.Instruction end;
    private final boolean endsAfterLastInstruction;

    TryTargets(com.debughelper.tools.r8.code.Instruction start, com.debughelper.tools.r8.code.Instruction end, boolean endsAfterLastInstruction) {
      assert start != null;
      assert end != null;
      this.start = start;
      this.end = end;
      this.endsAfterLastInstruction = endsAfterLastInstruction;
    }

    void replaceTarget(com.debughelper.tools.r8.code.Instruction target, com.debughelper.tools.r8.code.Instruction newTarget) {
      if (start == target) {
        start = newTarget;
      }
      if (end == target) {
        end = newTarget;
      }
    }

    int getStartOffset() {
      return start.getOffset();
    }

    int getStartToEndDelta() {
      if (endsAfterLastInstruction) {
        return end.getOffset() + end.getSize() - start.getOffset();
      }
      return end.getOffset() - start.getOffset();
    }
  }

  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final com.debughelper.tools.r8.graph.DexString firstJumboString;
  private final com.debughelper.tools.r8.graph.DexItemFactory factory;
  private final Map<com.debughelper.tools.r8.code.Instruction, List<com.debughelper.tools.r8.code.Instruction>> instructionTargets = new IdentityHashMap<>();
  private final Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> debugEventTargets
      = new Int2ReferenceOpenHashMap<>();
  private final Map<com.debughelper.tools.r8.code.Instruction, com.debughelper.tools.r8.code.Instruction> payloadToSwitch = new IdentityHashMap<>();
  private final Map<com.debughelper.tools.r8.graph.DexCode.Try, TryTargets> tryTargets = new IdentityHashMap<>();
  private final Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> tryRangeStartAndEndTargets
      = new Int2ReferenceOpenHashMap<>();
  private final Map<com.debughelper.tools.r8.graph.DexCode.TryHandler, List<com.debughelper.tools.r8.code.Instruction>> handlerTargets = new IdentityHashMap<>();

  public JumboStringRewriter(
          DexEncodedMethod method, DexString firstJumboString, DexItemFactory factory) {
    this.method = method;
    this.firstJumboString = firstJumboString;
    this.factory = factory;
  }

  public void rewrite() {
    // Build maps from everything in the code that uses offsets or direct addresses to reference
    // instructions to the actual instruction referenced.
    recordTargets();
    // Expand the code by rewriting jumbo strings and branching instructions.
    List<com.debughelper.tools.r8.code.Instruction> newInstructions = expandCode();
    // Commit to the new instruction offsets and update instructions, try-catch structures
    // and debug info with the new offsets.
    rewriteInstructionOffsets(newInstructions);
    com.debughelper.tools.r8.graph.DexCode.Try[] newTries = rewriteTryOffsets();
    com.debughelper.tools.r8.graph.DexCode.TryHandler[] newHandlers = rewriteHandlerOffsets();
    com.debughelper.tools.r8.graph.DexDebugInfo newDebugInfo = rewriteDebugInfoOffsets();
    // Set the new code on the method.
    com.debughelper.tools.r8.graph.DexCode code = method.getCode().asDexCode();
    // As we have rewritten the code, we now know that its highest string index that is not
    // a jumbo-string is firstJumboString (actually the previous string, but we do not have that).
    method.setDexCode(new com.debughelper.tools.r8.graph.DexCode(
        code.registerSize,
        code.incomingRegisterSize,
        code.outgoingRegisterSize,
        newInstructions.toArray(new com.debughelper.tools.r8.code.Instruction[newInstructions.size()]),
        newTries,
        newHandlers,
        newDebugInfo,
        firstJumboString));
  }

  private void rewriteInstructionOffsets(List<com.debughelper.tools.r8.code.Instruction> instructions) {
    for (com.debughelper.tools.r8.code.Instruction instruction : instructions) {
      if (instruction instanceof com.debughelper.tools.r8.code.Format22t) {  // IfEq, IfGe, IfGt, IfLe, IfLt, IfNe
        com.debughelper.tools.r8.code.Format22t condition = (com.debughelper.tools.r8.code.Format22t) instruction;
        int offset = instructionTargets.get(condition).get(0).getOffset() - instruction.getOffset();
        assert Short.MIN_VALUE <= offset && offset <= Short.MAX_VALUE;
        condition.CCCC = (short) offset;
      } else if (instruction instanceof Format21t) {  // IfEqz, IfGez, IfGtz, IfLez, IfLtz, IfNez
        Format21t condition = (Format21t) instruction;
        int offset = instructionTargets.get(condition).get(0).getOffset() - instruction.getOffset();
        assert Short.MIN_VALUE <= offset && offset <= Short.MAX_VALUE;
        condition.BBBB = (short) offset;
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto) {
        com.debughelper.tools.r8.code.Goto jump = (com.debughelper.tools.r8.code.Goto) instruction;
        int offset = instructionTargets.get(jump).get(0).getOffset() - instruction.getOffset();
        assert Byte.MIN_VALUE <= offset && offset <= Byte.MAX_VALUE;
        jump.AA = (byte) offset;
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto16) {
        com.debughelper.tools.r8.code.Goto16 jump = (com.debughelper.tools.r8.code.Goto16) instruction;
        int offset = instructionTargets.get(jump).get(0).getOffset() - instruction.getOffset();
        assert Short.MIN_VALUE <= offset && offset <= Short.MAX_VALUE;
        jump.AAAA = (short) offset;
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto32) {
        com.debughelper.tools.r8.code.Goto32 jump = (com.debughelper.tools.r8.code.Goto32) instruction;
        int offset = instructionTargets.get(jump).get(0).getOffset() - instruction.getOffset();
        jump.AAAAAAAA = offset;
      } else if (instruction.hasPayload()) {  // FillArrayData, SparseSwitch, PackedSwitch
        com.debughelper.tools.r8.code.Format31t payloadUser = (com.debughelper.tools.r8.code.Format31t) instruction;
        int offset =
            instructionTargets.get(payloadUser).get(0).getOffset() - instruction.getOffset();
        payloadUser.setPayloadOffset(offset);
      } else if (instruction instanceof com.debughelper.tools.r8.code.SwitchPayload) {
        com.debughelper.tools.r8.code.SwitchPayload payload = (com.debughelper.tools.r8.code.SwitchPayload) instruction;
        com.debughelper.tools.r8.code.Instruction switchInstruction = payloadToSwitch.get(payload);
        List<com.debughelper.tools.r8.code.Instruction> switchTargets = instructionTargets.get(payload);
        int[] targets = payload.switchTargetOffsets();
        for (int i = 0; i < switchTargets.size(); i++) {
          com.debughelper.tools.r8.code.Instruction target = switchTargets.get(i);
          targets[i] = target.getOffset() - switchInstruction.getOffset();
        }
      }
    }
  }

  private com.debughelper.tools.r8.graph.DexCode.Try[] rewriteTryOffsets() {
    com.debughelper.tools.r8.graph.DexCode code = method.getCode().asDexCode();
    com.debughelper.tools.r8.graph.DexCode.Try[] result = new com.debughelper.tools.r8.graph.DexCode.Try[code.tries.length];
    for (int i = 0; i < code.tries.length; i++) {
      com.debughelper.tools.r8.graph.DexCode.Try theTry = code.tries[i];
      TryTargets targets = tryTargets.get(theTry);
      result[i] = new com.debughelper.tools.r8.graph.DexCode.Try(targets.getStartOffset(), targets.getStartToEndDelta(), -1);
      result[i].handlerIndex = theTry.handlerIndex;
    }
    return result;
  }

  private com.debughelper.tools.r8.graph.DexCode.TryHandler[] rewriteHandlerOffsets() {
    com.debughelper.tools.r8.graph.DexCode code = method.getCode().asDexCode();
    if (code.handlers == null) {
      return null;
    }
    com.debughelper.tools.r8.graph.DexCode.TryHandler[] result = new com.debughelper.tools.r8.graph.DexCode.TryHandler[code.handlers.length];
    for (int i = 0; i < code.handlers.length; i++) {
      com.debughelper.tools.r8.graph.DexCode.TryHandler handler = code.handlers[i];
      List<com.debughelper.tools.r8.code.Instruction> targets = handlerTargets.get(handler);
      Iterator<com.debughelper.tools.r8.code.Instruction> it = targets.iterator();
      int catchAllAddr = com.debughelper.tools.r8.graph.DexCode.TryHandler.NO_HANDLER;
      if (handler.catchAllAddr != com.debughelper.tools.r8.graph.DexCode.TryHandler.NO_HANDLER) {
        catchAllAddr = it.next().getOffset();
      }
      com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair[] newPairs = new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair[handler.pairs.length];
      for (int j = 0; j < handler.pairs.length; j++) {
        com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair pair = handler.pairs[j];
        newPairs[j] = new com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair(pair.type, it.next().getOffset());
      }
      result[i] = new com.debughelper.tools.r8.graph.DexCode.TryHandler(newPairs, catchAllAddr);
    }
    return result;
  }

  private com.debughelper.tools.r8.graph.DexDebugInfo rewriteDebugInfoOffsets() {
    com.debughelper.tools.r8.graph.DexCode code = method.getCode().asDexCode();
    if (debugEventTargets.size() != 0) {
      int lastOriginalOffset = 0;
      int lastNewOffset = 0;
      List<com.debughelper.tools.r8.graph.DexDebugEvent> events = new ArrayList<>();
      for (com.debughelper.tools.r8.graph.DexDebugEvent event : code.getDebugInfo().events) {
        if (event instanceof com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC) {
          com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC advance = (com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC) event;
          lastOriginalOffset += advance.delta;
          com.debughelper.tools.r8.code.Instruction target = debugEventTargets.get(lastOriginalOffset);
          int pcDelta = target.getOffset() - lastNewOffset;
          events.add(factory.createAdvancePC(pcDelta));
          lastNewOffset = target.getOffset();
        } else if (event instanceof com.debughelper.tools.r8.graph.DexDebugEvent.Default) {
          com.debughelper.tools.r8.graph.DexDebugEvent.Default defaultEvent = (com.debughelper.tools.r8.graph.DexDebugEvent.Default) event;
          lastOriginalOffset += defaultEvent.getPCDelta();
          com.debughelper.tools.r8.code.Instruction target = debugEventTargets.get(lastOriginalOffset);
          int lineDelta = defaultEvent.getLineDelta();
          int pcDelta = target.getOffset() - lastNewOffset;
          addDefaultEvent(lineDelta, pcDelta, events);
          lastNewOffset = target.getOffset();
        } else {
          events.add(event);
        }
      }
      return new com.debughelper.tools.r8.graph.DexDebugInfo(
          code.getDebugInfo().startLine,
          code.getDebugInfo().parameters,
          events.toArray(new com.debughelper.tools.r8.graph.DexDebugEvent[events.size()]));
    }
    return code.getDebugInfo();
  }

  // Add a default event. If the lineDelta and pcDelta can be encoded in one default event
  // that will be done. Otherwise, this can output an advance line and/or advance pc event
  // followed by a default event. A default event is always emitted as that is what will
  // materialize an entry in the line table.
  private void addDefaultEvent(int lineDelta, int pcDelta, List<com.debughelper.tools.r8.graph.DexDebugEvent> events) {
    if (lineDelta < Constants.DBG_LINE_BASE
        || lineDelta - Constants.DBG_LINE_BASE >= Constants.DBG_LINE_RANGE) {
      events.add(factory.createAdvanceLine(lineDelta));
      lineDelta = 0;
    }
    if (pcDelta >= Constants.DBG_ADDRESS_RANGE) {
      events.add(factory.createAdvancePC(pcDelta));
      pcDelta = 0;
    }
    int specialOpcode =
        0x0a + (lineDelta - Constants.DBG_LINE_BASE) + Constants.DBG_LINE_RANGE * pcDelta;
    assert specialOpcode >= 0x0a;
    assert specialOpcode <= 0xff;
    events.add(factory.createDefault(specialOpcode));
  }

  private List<com.debughelper.tools.r8.code.Instruction> expandCode() {
    LinkedList<com.debughelper.tools.r8.code.Instruction> instructions = new LinkedList<>();
    Collections.addAll(instructions, method.getCode().asDexCode().instructions);
    int offsetDelta;
    do {
      ListIterator<com.debughelper.tools.r8.code.Instruction> it = instructions.listIterator();
      offsetDelta = 0;
      while (it.hasNext()) {
        com.debughelper.tools.r8.code.Instruction instruction = it.next();
        int orignalOffset = instruction.getOffset();
        instruction.setOffset(orignalOffset + offsetDelta);
        if (instruction instanceof com.debughelper.tools.r8.code.ConstString) {
          com.debughelper.tools.r8.code.ConstString string = (ConstString) instruction;
          if (string.getString().compareTo(firstJumboString) >= 0) {
            com.debughelper.tools.r8.code.ConstStringJumbo jumboString = new ConstStringJumbo(string.AA, string.getString());
            jumboString.setOffset(string.getOffset());
            offsetDelta++;
            it.set(jumboString);
            replaceTarget(instruction, jumboString);
          }
        } else if (instruction instanceof com.debughelper.tools.r8.code.Format22t) {  // IfEq, IfGe, IfGt, IfLe, IfLt, IfNe
          com.debughelper.tools.r8.code.Format22t condition = (com.debughelper.tools.r8.code.Format22t) instruction;
          int offset =
              instructionTargets.get(condition).get(0).getOffset() - instruction.getOffset();
          if (Short.MIN_VALUE > offset || offset > Short.MAX_VALUE) {
            com.debughelper.tools.r8.code.Format22t newCondition = null;
            switch (condition.getType().inverted()) {
              case EQ:
                newCondition = new IfEq(condition.A, condition.B, 0);
                break;
              case GE:
                newCondition = new IfGe(condition.A, condition.B, 0);
                break;
              case GT:
                newCondition = new IfGt(condition.A, condition.B, 0);
                break;
              case LE:
                newCondition = new IfLe(condition.A, condition.B, 0);
                break;
              case LT:
                newCondition = new IfLt(condition.A, condition.B, 0);
                break;
              case NE:
                newCondition = new IfNe(condition.A, condition.B, 0);
                break;
            }
            offsetDelta = rewriteIfToIfAndGoto(offsetDelta, it, condition, newCondition);
          }
        } else if (instruction instanceof Format21t) {  // IfEqz, IfGez, IfGtz, IfLez, IfLtz, IfNez
          Format21t condition = (Format21t) instruction;
          int offset =
              instructionTargets.get(condition).get(0).getOffset() - instruction.getOffset();
          if (Short.MIN_VALUE > offset || offset > Short.MAX_VALUE) {
            Format21t newCondition = null;
            switch (condition.getType().inverted()) {
              case EQ:
                newCondition = new IfEqz(condition.AA, 0);
                break;
              case GE:
                newCondition = new IfGez(condition.AA, 0);
                break;
              case GT:
                newCondition = new IfGtz(condition.AA, 0);
                break;
              case LE:
                newCondition = new IfLez(condition.AA, 0);
                break;
              case LT:
                newCondition = new IfLtz(condition.AA, 0);
                break;
              case NE:
                newCondition = new IfNez(condition.AA, 0);
                break;
            }
            offsetDelta = rewriteIfToIfAndGoto(offsetDelta, it, condition, newCondition);
          }
        } else if (instruction instanceof com.debughelper.tools.r8.code.Goto) {
          com.debughelper.tools.r8.code.Goto jump = (com.debughelper.tools.r8.code.Goto) instruction;
          int offset =
              instructionTargets.get(jump).get(0).getOffset() - instruction.getOffset();
          if (Byte.MIN_VALUE > offset || offset > Byte.MAX_VALUE) {
            com.debughelper.tools.r8.code.Instruction newJump;
            if (Short.MIN_VALUE > offset || offset > Short.MAX_VALUE) {
              newJump = new com.debughelper.tools.r8.code.Goto32(offset);
            } else {
              newJump = new com.debughelper.tools.r8.code.Goto16(offset);
            }
            newJump.setOffset(jump.getOffset());
            it.set(newJump);
            offsetDelta += (newJump.getSize() - jump.getSize());
            replaceTarget(jump, newJump);
            List<com.debughelper.tools.r8.code.Instruction> targets = instructionTargets.remove(jump);
            instructionTargets.put(newJump, targets);
          }
        } else if (instruction instanceof com.debughelper.tools.r8.code.Goto16) {
          com.debughelper.tools.r8.code.Goto16 jump = (com.debughelper.tools.r8.code.Goto16) instruction;
          int offset =
              instructionTargets.get(jump).get(0).getOffset() - instruction.getOffset();
          if (Short.MIN_VALUE > offset || offset > Short.MAX_VALUE) {
            com.debughelper.tools.r8.code.Instruction newJump = new com.debughelper.tools.r8.code.Goto32(offset);
            newJump.setOffset(jump.getOffset());
            it.set(newJump);
            offsetDelta += (newJump.getSize() - jump.getSize());
            replaceTarget(jump, newJump);
            List<com.debughelper.tools.r8.code.Instruction> targets = instructionTargets.remove(jump);
            instructionTargets.put(newJump, targets);
          }
        } else if (instruction instanceof com.debughelper.tools.r8.code.Goto32) {
          // Instruction big enough for any offset.
        } else if (instruction.hasPayload()) {  // FillArrayData, SparseSwitch, PackedSwitch
          // Instruction big enough for any offset.
        } else if (instruction.isPayload()) {
          // Payload instructions must be 4 byte aligned (instructions are 2 bytes).
          if (instruction.getOffset() % 2 != 0) {
            it.previous();
            // Check if the previous instruction was a simple nop. If that is the case, remove it
            // to make the alignment instead of adding another one. Only allow removal if this
            // instruction is not targeted by anything. See b/78072750.
            com.debughelper.tools.r8.code.Instruction instructionBeforePayload = it.hasPrevious() ? it.previous() : null;
            if (instructionBeforePayload != null
                && instructionBeforePayload.isSimpleNop()
                && debugEventTargets.get(orignalOffset) == null
                && tryRangeStartAndEndTargets.get(orignalOffset) == null) {
              it.remove();
              offsetDelta--;
            } else {
              if (instructionBeforePayload != null) {
                it.next();
              }
              com.debughelper.tools.r8.code.Nop nop = new Nop();
              nop.setOffset(instruction.getOffset());
              it.add(nop);
              offsetDelta++;
            }
            instruction.setOffset(orignalOffset + offsetDelta);
            it.next();
          }
          // Instruction big enough for any offset.
        }
      }
    } while (offsetDelta > 0);
    return instructions;
  }

  private int rewriteIfToIfAndGoto(
      int offsetDelta,
      ListIterator<com.debughelper.tools.r8.code.Instruction> it,
      com.debughelper.tools.r8.code.Instruction condition,
      com.debughelper.tools.r8.code.Instruction newCondition) {
    int jumpOffset = condition.getOffset() + condition.getSize();
    com.debughelper.tools.r8.code.Goto32 jump = new com.debughelper.tools.r8.code.Goto32(0);
    jump.setOffset(jumpOffset);
    newCondition.setOffset(condition.getOffset());
    it.set(newCondition);
    replaceTarget(condition, newCondition);
    it.add(jump);
    offsetDelta += jump.getSize();
    instructionTargets.put(jump, instructionTargets.remove(condition));
    com.debughelper.tools.r8.code.Instruction fallthroughInstruction = it.next();
    instructionTargets.put(newCondition, Lists.newArrayList(fallthroughInstruction));
    it.previous();
    return offsetDelta;
  }

  private void replaceTarget(com.debughelper.tools.r8.code.Instruction target, com.debughelper.tools.r8.code.Instruction newTarget) {
    for (List<com.debughelper.tools.r8.code.Instruction> instructions : instructionTargets.values()) {
      instructions.replaceAll((i) -> i == target ? newTarget : i);
    }
    for (Int2ReferenceMap.Entry<com.debughelper.tools.r8.code.Instruction> entry : debugEventTargets.int2ReferenceEntrySet()) {
      if (entry.getValue() == target) {
        entry.setValue(newTarget);
      }
    }
    for (Entry<com.debughelper.tools.r8.graph.DexCode.Try, TryTargets> entry : tryTargets.entrySet()) {
      entry.getValue().replaceTarget(target, newTarget);
    }
    for (List<com.debughelper.tools.r8.code.Instruction> instructions : handlerTargets.values()) {
      instructions.replaceAll((i) -> i == target ? newTarget : i);
    }
  }

  private void recordInstructionTargets(Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> offsetToInstruction) {
    com.debughelper.tools.r8.code.Instruction[] instructions = method.getCode().asDexCode().instructions;
    for (com.debughelper.tools.r8.code.Instruction instruction : instructions) {
      if (instruction instanceof com.debughelper.tools.r8.code.Format22t) {  // IfEq, IfGe, IfGt, IfLe, IfLt, IfNe
        com.debughelper.tools.r8.code.Format22t condition = (Format22t) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(condition.getOffset() + condition.CCCC);
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction instanceof Format21t) {  // IfEqz, IfGez, IfGtz, IfLez, IfLtz, IfNez
        Format21t condition = (Format21t) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(condition.getOffset() + condition.BBBB);
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto) {
        com.debughelper.tools.r8.code.Goto jump = (Goto) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(jump.getOffset() + jump.AA);
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto16) {
        com.debughelper.tools.r8.code.Goto16 jump = (Goto16) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(jump.getOffset() + jump.AAAA);
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction instanceof com.debughelper.tools.r8.code.Goto32) {
        com.debughelper.tools.r8.code.Goto32 jump = (Goto32) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(jump.getOffset() + jump.AAAAAAAA);
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction.hasPayload()) {  // FillArrayData, SparseSwitch, PackedSwitch
        com.debughelper.tools.r8.code.Format31t offsetInstruction = (Format31t) instruction;
        com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(
            offsetInstruction.getOffset() + offsetInstruction.getPayloadOffset());
        assert target != null;
        instructionTargets.put(instruction, Lists.newArrayList(target));
      } else if (instruction instanceof com.debughelper.tools.r8.code.SwitchPayload) {
        com.debughelper.tools.r8.code.SwitchPayload payload = (SwitchPayload) instruction;
        int[] targetOffsets = payload.switchTargetOffsets();
        int switchOffset = payloadToSwitch.get(instruction).getOffset();
        List<com.debughelper.tools.r8.code.Instruction> targets = new ArrayList<>();
        for (int i = 0; i < targetOffsets.length; i++) {
          com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(switchOffset + targetOffsets[i]);
          assert target != null;
          targets.add(target);
        }
        instructionTargets.put(instruction, targets);
      }
    }
  }

  private void recordDebugEventTargets(Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> offsetToInstruction) {
    DexDebugInfo debugInfo = method.getCode().asDexCode().getDebugInfo();
    if (debugInfo != null) {
      int address = 0;
      for (com.debughelper.tools.r8.graph.DexDebugEvent event : debugInfo.events) {
        if (event instanceof com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC) {
          com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC advance = (com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC) event;
          address += advance.delta;
          com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(address);
          assert target != null;
          debugEventTargets.put(address, target);
        } else if (event instanceof com.debughelper.tools.r8.graph.DexDebugEvent.Default) {
          com.debughelper.tools.r8.graph.DexDebugEvent.Default defaultEvent = (com.debughelper.tools.r8.graph.DexDebugEvent.Default) event;
          address += defaultEvent.getPCDelta();
          com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(address);
          assert target != null;
          debugEventTargets.put(address, target);
        }
      }
    }
  }

  private void recordTryAndHandlerTargets(
      Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> offsetToInstruction,
      com.debughelper.tools.r8.code.Instruction lastInstruction) {
    com.debughelper.tools.r8.graph.DexCode code = method.getCode().asDexCode();
    for (com.debughelper.tools.r8.graph.DexCode.Try theTry : code.tries) {
      com.debughelper.tools.r8.code.Instruction start = offsetToInstruction.get(theTry.startAddress);
      com.debughelper.tools.r8.code.Instruction end = null;
      int endAddress = theTry.startAddress + theTry.instructionCount;
      TryTargets targets;
      if (endAddress > lastInstruction.getOffset()) {
        end = lastInstruction;
        targets = new TryTargets(start, lastInstruction, true);
      } else {
        end = offsetToInstruction.get(endAddress);
        targets = new TryTargets(start, end, false);
      }
      assert theTry.startAddress == targets.getStartOffset();
      assert theTry.instructionCount == targets.getStartToEndDelta();
      tryTargets.put(theTry, targets);
      tryRangeStartAndEndTargets.put(start.getOffset(), start);
      tryRangeStartAndEndTargets.put(end.getOffset(), end);
    }
    if (code.handlers != null) {
      for (com.debughelper.tools.r8.graph.DexCode.TryHandler handler : code.handlers) {
        List<com.debughelper.tools.r8.code.Instruction> targets = new ArrayList<>();
        if (handler.catchAllAddr != com.debughelper.tools.r8.graph.DexCode.TryHandler.NO_HANDLER) {
          com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(handler.catchAllAddr);
          assert target != null;
          targets.add(target);
        }
        for (com.debughelper.tools.r8.graph.DexCode.TryHandler.TypeAddrPair pair : handler.pairs) {
          com.debughelper.tools.r8.code.Instruction target = offsetToInstruction.get(pair.addr);
          assert target != null;
          targets.add(target);
        }
        handlerTargets.put(handler, targets);
      }
    }
  }

  private void recordTargets() {
    Int2ReferenceMap<com.debughelper.tools.r8.code.Instruction> offsetToInstruction = new Int2ReferenceOpenHashMap<>();
    com.debughelper.tools.r8.code.Instruction[] instructions = method.getCode().asDexCode().instructions;
    boolean containsPayloads = false;
    for (com.debughelper.tools.r8.code.Instruction instruction : instructions) {
      offsetToInstruction.put(instruction.getOffset(), instruction);
      if (instruction.hasPayload()) {  // FillArrayData, SparseSwitch, PackedSwitch
        containsPayloads = true;
      }
    }
    if (containsPayloads) {
      for (com.debughelper.tools.r8.code.Instruction instruction : instructions) {
        if (instruction.hasPayload()) {  // FillArrayData, SparseSwitch, PackedSwitch
          com.debughelper.tools.r8.code.Instruction payload =
              offsetToInstruction.get(instruction.getOffset() + instruction.getPayloadOffset());
          assert payload != null;
          payloadToSwitch.put(payload, instruction);
        }
      }
    }
    recordInstructionTargets(offsetToInstruction);
    recordDebugEventTargets(offsetToInstruction);
    Instruction lastInstruction = instructions[instructions.length - 1];
    recordTryAndHandlerTargets(offsetToInstruction, lastInstruction);
  }
}
