// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InvokeDirect;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.utils.InternalOptions;

import it.unimi.dsi.fastutil.objects.Reference2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Extracts the ordinal values for all Enum classes from their static initializer.
 * <p>
 * An Enum class has a field for each value. In the class initializer, each field is initialized
 * to a singleton object that represents the value. This code matches on the corresponding call
 * to the constructor (instance initializer) and extracts the value of the second argument, which
 * is the ordinal.
 */
public class EnumOrdinalMapCollector {

  private final Enqueuer.AppInfoWithLiveness appInfo;
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  private final Map<DexType, Reference2IntMap<com.debughelper.tools.r8.graph.DexField>> ordinalsMaps = new IdentityHashMap<>();

  public EnumOrdinalMapCollector(Enqueuer.AppInfoWithLiveness appInfo, InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
  }

  public Enqueuer.AppInfoWithLiveness run() {
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : appInfo.classes()) {
      processClasses(clazz);
    }
    if (!ordinalsMaps.isEmpty()) {
      return appInfo.addEnumOrdinalMaps(ordinalsMaps);
    }
    return appInfo;
  }

  private void processClasses(DexProgramClass clazz) {
    // Enum classes are flagged as such. Also, for library classes, the ordinals are not known.
    if (!clazz.accessFlags.isEnum() || clazz.isLibraryClass() || !clazz.hasClassInitializer()) {
      return;
    }
    DexEncodedMethod initializer = clazz.getClassInitializer();
    IRCode code = initializer.getCode().buildIR(initializer, appInfo, options, clazz.origin);
    Reference2IntMap<DexField> ordinalsMap = new Reference2IntArrayMap<>();
    ordinalsMap.defaultReturnValue(-1);
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction insn = it.next();
      if (!insn.isStaticPut()) {
        continue;
      }
      StaticPut staticPut = insn.asStaticPut();
      if (staticPut.getField().type != clazz.type) {
        continue;
      }
      com.debughelper.tools.r8.ir.code.Instruction newInstance = staticPut.inValue().definition;
      if (newInstance == null || !newInstance.isNewInstance()) {
        continue;
      }
      com.debughelper.tools.r8.ir.code.Instruction ordinal = null;
      for (Instruction ctorCall : newInstance.outValue().uniqueUsers()) {
        if (!ctorCall.isInvokeDirect()) {
          continue;
        }
        InvokeDirect invoke = ctorCall.asInvokeDirect();
        if (!appInfo.dexItemFactory.isConstructor(invoke.getInvokedMethod())
            || invoke.arguments().size() < 3) {
          continue;
        }
        ordinal = invoke.arguments().get(2).definition;
        break;
      }
      if (ordinal == null || !ordinal.isConstNumber()) {
        return;
      }
      if (ordinalsMap.put(staticPut.getField(), ordinal.asConstNumber().getIntValue()) != -1) {
        return;
      }
    }
    ordinalsMaps.put(clazz.type, ordinalsMap);
  }
}
