// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.kotlin.KotlinInfo;
import com.debughelper.tools.r8.origin.Origin;

import java.util.List;
import java.util.function.Supplier;

public class DexLibraryClass extends DexClass implements Supplier<DexLibraryClass> {

  public DexLibraryClass(
      com.debughelper.tools.r8.graph.DexType type,
      com.debughelper.tools.r8.ProgramResource.Kind kind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      EnclosingMethodAttribute enclosingMember,
      List<InnerClassAttribute> innerClasses,
      DexAnnotationSet annotations,
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
      com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
      DexEncodedMethod[] virtualMethods,
      boolean skipNameValidationForTesting) {
    super(
        sourceFile,
        interfaces,
        accessFlags,
        superType,
        type,
        staticFields,
        instanceFields,
        directMethods,
        virtualMethods,
        enclosingMember,
        innerClasses,
        annotations,
        origin,
        skipNameValidationForTesting);
    // Set all static field values to unknown. We don't want to use the value from the library
    // at compile time, as it can be different at runtime.
    for (DexEncodedField staticField : staticFields) {
      staticField.setStaticValue(DexValue.UNKNOWN);
    }
    assert kind == com.debughelper.tools.r8.ProgramResource.Kind.CF : "Invalid kind " + kind + " for library-path class " + type;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
                                  DexMethod method, int instructionOffset) {
    throw new Unreachable();
  }

  @Override
  public String toString() {
    return type.toString() + "(library class)";
  }

  @Override
  public String toSourceString() {
    return type.toSourceString() + "(library class)";
  }

  @Override
  public void addDependencies(MixedSectionCollection collector) {
    // Should never happen but does not harm.
    assert false;
  }

  @Override
  public boolean isLibraryClass() {
    return true;
  }

  @Override
  public DexLibraryClass asLibraryClass() {
    return this;
  }

  @Override
  public KotlinInfo getKotlinInfo() {
    return null;
  }

  @Override
  public DexLibraryClass get() {
    return this;
  }
}
