// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.optimize.SwitchMapCollector;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.ArrayGet;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InvokeVirtual;
import com.debughelper.tools.r8.ir.code.StaticGet;
import com.debughelper.tools.r8.shaking.Enqueuer;

import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;

public class SwitchUtils {

  public static final class EnumSwitchInfo {
    public final com.debughelper.tools.r8.graph.DexType enumClass;
    public final com.debughelper.tools.r8.ir.code.Instruction ordinalInvoke;
    public final com.debughelper.tools.r8.ir.code.Instruction arrayGet;
    public final com.debughelper.tools.r8.ir.code.Instruction staticGet;
    public final Int2ReferenceMap<com.debughelper.tools.r8.graph.DexField> indexMap;
    public final Reference2IntMap<com.debughelper.tools.r8.graph.DexField> ordinalsMap;

    private EnumSwitchInfo(com.debughelper.tools.r8.graph.DexType enumClass,
                           com.debughelper.tools.r8.ir.code.Instruction ordinalInvoke,
                           com.debughelper.tools.r8.ir.code.Instruction arrayGet, com.debughelper.tools.r8.ir.code.Instruction staticGet,
                           Int2ReferenceMap<com.debughelper.tools.r8.graph.DexField> indexMap,
                           Reference2IntMap<com.debughelper.tools.r8.graph.DexField> ordinalsMap) {
      this.enumClass = enumClass;
      this.ordinalInvoke = ordinalInvoke;
      this.arrayGet = arrayGet;
      this.staticGet = staticGet;
      this.indexMap = indexMap;
      this.ordinalsMap = ordinalsMap;
    }
  }

  /**
   * Looks for a switch statement over the enum companion class of the form
   *
   * <blockquote><pre>
   * switch(CompanionClass.$switchmap$field[enumValue.ordinal()]) {
   *   ...
   * }
   * </pre></blockquote>
   *
   * and extracts the components and the index and ordinal maps. See
   * {@link EnumOrdinalMapCollector} and
   * {@link SwitchMapCollector} for details.
   */
  public static EnumSwitchInfo analyzeSwitchOverEnum(com.debughelper.tools.r8.ir.code.Instruction switchInsn,
                                                     Enqueuer.AppInfoWithLiveness appInfo) {
    com.debughelper.tools.r8.ir.code.Instruction input = switchInsn.inValues().get(0).definition;
    if (input == null || !input.isArrayGet()) {
      return null;
    }
    ArrayGet arrayGet = input.asArrayGet();
    com.debughelper.tools.r8.ir.code.Instruction index = arrayGet.index().definition;
    if (index == null || !index.isInvokeVirtual()) {
      return null;
    }
    InvokeVirtual ordinalInvoke = index.asInvokeVirtual();
    DexMethod ordinalMethod = ordinalInvoke.getInvokedMethod();
    DexClass enumClass = appInfo.definitionFor(ordinalMethod.holder);
    DexItemFactory dexItemFactory = appInfo.dexItemFactory;
    // After member rebinding, enumClass will be the actual java.lang.Enum class.
    if (enumClass == null
        || (!enumClass.accessFlags.isEnum() && enumClass.type != dexItemFactory.enumType)
        || ordinalMethod.name != dexItemFactory.ordinalMethodName
        || ordinalMethod.proto.returnType != dexItemFactory.intType
        || !ordinalMethod.proto.parameters.isEmpty()) {
      return null;
    }
    Instruction array = arrayGet.array().definition;
    if (array == null || !array.isStaticGet()) {
      return null;
    }
    StaticGet staticGet = array.asStaticGet();
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DexField> indexMap = appInfo.getSwitchMapFor(staticGet.getField());
    if (indexMap == null || indexMap.isEmpty()) {
      return null;
    }
    // Due to member rebinding, only the fields are certain to provide the actual enums
    // class.
    DexType enumType = indexMap.values().iterator().next().getHolder();
    Reference2IntMap<DexField> ordinalsMap = appInfo.getOrdinalsMapFor(enumType);
    if (ordinalsMap == null) {
      return null;
    }
    return new EnumSwitchInfo(enumType, ordinalInvoke, arrayGet, staticGet, indexMap,
        ordinalsMap);
  }


}
