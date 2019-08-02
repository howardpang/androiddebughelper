// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ProgramResource.Kind;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexEncodedArray;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.kotlin.KotlinInfo;
import com.debughelper.tools.r8.origin.Origin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class DexProgramClass extends DexClass implements Supplier<DexProgramClass> {

  private static final com.debughelper.tools.r8.graph.DexEncodedArray SENTINEL_NOT_YET_COMPUTED =
      new com.debughelper.tools.r8.graph.DexEncodedArray(new com.debughelper.tools.r8.graph.DexValue[0]);

  private final com.debughelper.tools.r8.ProgramResource.Kind originKind;
  private com.debughelper.tools.r8.graph.DexEncodedArray staticValues = SENTINEL_NOT_YET_COMPUTED;
  private final Collection<DexProgramClass> synthesizedFrom;
  private int classFileVersion = -1;
  private com.debughelper.tools.r8.kotlin.KotlinInfo kotlinInfo = null;

  public DexProgramClass(
      com.debughelper.tools.r8.graph.DexType type,
      com.debughelper.tools.r8.ProgramResource.Kind originKind,
      com.debughelper.tools.r8.origin.Origin origin,
      ClassAccessFlags accessFlags,
      com.debughelper.tools.r8.graph.DexType superType,
      com.debughelper.tools.r8.graph.DexTypeList interfaces,
      DexString sourceFile,
      EnclosingMethodAttribute enclosingMember,
      List<InnerClassAttribute> innerClasses,
      com.debughelper.tools.r8.graph.DexAnnotationSet classAnnotations,
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
      com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods,
      boolean skipNameValidationForTesting) {
    this(
        type,
        originKind,
        origin,
        accessFlags,
        superType,
        interfaces,
        sourceFile,
        enclosingMember,
        innerClasses,
        classAnnotations,
        staticFields,
        instanceFields,
        directMethods,
        virtualMethods,
        skipNameValidationForTesting,
        Collections.emptyList());
  }

  public DexProgramClass(
      com.debughelper.tools.r8.graph.DexType type,
      com.debughelper.tools.r8.ProgramResource.Kind originKind,
      Origin origin,
      ClassAccessFlags accessFlags,
      DexType superType,
      DexTypeList interfaces,
      DexString sourceFile,
      EnclosingMethodAttribute enclosingMember,
      List<InnerClassAttribute> innerClasses,
      DexAnnotationSet classAnnotations,
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
      com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods,
      boolean skipNameValidationForTesting,
      Collection<DexProgramClass> synthesizedDirectlyFrom) {
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
        classAnnotations,
        origin,
        skipNameValidationForTesting);
    assert classAnnotations != null;
    this.originKind = originKind;
    this.synthesizedFrom = accumulateSynthesizedFrom(new HashSet<>(), synthesizedDirectlyFrom);
  }

  public boolean originatesFromDexResource() {
    return originKind == com.debughelper.tools.r8.ProgramResource.Kind.DEX;
  }

  public boolean originatesFromClassResource() {
    return originKind == com.debughelper.tools.r8.ProgramResource.Kind.CF;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
      DexMethod method, int instructionOffset) {
    if (indexedItems.addClass(this)) {
      type.collectIndexedItems(indexedItems, method, instructionOffset);
      if (superType != null) {
        superType.collectIndexedItems(indexedItems, method, instructionOffset);
      } else {
        assert type.toDescriptorString().equals("Ljava/lang/Object;");
      }
      if (sourceFile != null) {
        sourceFile.collectIndexedItems(indexedItems, method, instructionOffset);
      }
      if (annotations != null) {
        annotations.collectIndexedItems(indexedItems, method, instructionOffset);
      }
      if (interfaces != null) {
        interfaces.collectIndexedItems(indexedItems, method, instructionOffset);
      }
      if (getEnclosingMethod() != null) {
        getEnclosingMethod().collectIndexedItems(indexedItems);
      }
      for (InnerClassAttribute attribute : getInnerClasses()) {
        attribute.collectIndexedItems(indexedItems);
      }
      synchronizedCollectAll(indexedItems, staticFields);
      synchronizedCollectAll(indexedItems, instanceFields);
      synchronizedCollectAll(indexedItems, directMethods);
      synchronizedCollectAll(indexedItems, virtualMethods);
    }
  }

  private static <T extends DexItem> void synchronizedCollectAll(IndexedItemCollection collection,
      T[] items) {
    synchronized (items) {
      DexItem.collectAll(collection, items);
    }
  }

  public Collection<DexProgramClass> getSynthesizedFrom() {
    return synthesizedFrom;
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    assert getEnclosingMethod() == null;
    assert getInnerClasses().isEmpty();
    if (hasAnnotations()) {
      mixedItems.setAnnotationsDirectoryForClass(this, new DexAnnotationDirectory(this));
    }
  }

  @Override
  public void addDependencies(MixedSectionCollection collector) {
    assert getEnclosingMethod() == null;
    assert getInnerClasses().isEmpty();
    // We only have a class data item if there are methods or fields.
    if (hasMethodsOrFields()) {
      collector.add(this);
      synchronizedCollectAll(collector, directMethods);
      synchronizedCollectAll(collector, virtualMethods);
      synchronizedCollectAll(collector, staticFields);
      synchronizedCollectAll(collector, instanceFields);
    }
    if (annotations != null) {
      annotations.collectMixedSectionItems(collector);
    }
    if (interfaces != null) {
      interfaces.collectMixedSectionItems(collector);
    }
    annotations.collectMixedSectionItems(collector);
  }

  private static <T extends DexItem> void synchronizedCollectAll(MixedSectionCollection collection,
      T[] items) {
    synchronized (items) {
      DexItem.collectAll(collection, items);
    }
  }

  @Override
  public String toString() {
    return type.toString();
  }

  @Override
  public String toSourceString() {
    return type.toSourceString();
  }

  @Override
  public boolean isProgramClass() {
    return true;
  }

  @Override
  public DexProgramClass asProgramClass() {
    return this;
  }

  @Override
  public com.debughelper.tools.r8.kotlin.KotlinInfo getKotlinInfo() {
    return kotlinInfo;
  }

  public void setKotlinInfo(KotlinInfo kotlinInfo) {
    assert this.kotlinInfo == null || kotlinInfo == null;
    this.kotlinInfo = kotlinInfo;
  }

  public boolean hasMethodsOrFields() {
    int numberOfFields = staticFields().length + instanceFields().length;
    int numberOfMethods = directMethods().length + virtualMethods().length;
    return numberOfFields + numberOfMethods > 0;
  }

  public boolean hasAnnotations() {
    return !annotations.isEmpty()
        || hasAnnotations(virtualMethods)
        || hasAnnotations(directMethods)
        || hasAnnotations(staticFields)
        || hasAnnotations(instanceFields);
  }

  boolean hasOnlyInternalizableAnnotations() {
    return !hasAnnotations(virtualMethods)
        && !hasAnnotations(directMethods)
        && !hasAnnotations(staticFields)
        && !hasAnnotations(instanceFields);
  }

  private boolean hasAnnotations(com.debughelper.tools.r8.graph.DexEncodedField[] fields) {
    synchronized (fields) {
      return Arrays.stream(fields).anyMatch(com.debughelper.tools.r8.graph.DexEncodedField::hasAnnotation);
    }
  }

  private boolean hasAnnotations(com.debughelper.tools.r8.graph.DexEncodedMethod[] methods) {
    synchronized (methods) {
      return Arrays.stream(methods).anyMatch(com.debughelper.tools.r8.graph.DexEncodedMethod::hasAnnotation);
    }
  }

  private static Collection<DexProgramClass> accumulateSynthesizedFrom(
      Set<DexProgramClass> accumulated,
      Collection<DexProgramClass> toAccumulate) {
    for (DexProgramClass dexProgramClass : toAccumulate) {
      if (dexProgramClass.synthesizedFrom.isEmpty()) {
        accumulated.add(dexProgramClass);
      } else {
        accumulateSynthesizedFrom(accumulated, dexProgramClass.synthesizedFrom);
      }
    }
    return accumulated;
  }

  public void computeStaticValues(DexItemFactory factory) {
    // It does not actually hurt to compute this multiple times. So racing on staticValues is OK.
    if (staticValues == SENTINEL_NOT_YET_COMPUTED) {
      synchronized (staticFields) {
        assert PresortedComparable.isSorted(staticFields);
        com.debughelper.tools.r8.graph.DexEncodedField[] fields = staticFields;
        int length = 0;
        List<com.debughelper.tools.r8.graph.DexValue> values = new ArrayList<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
          com.debughelper.tools.r8.graph.DexEncodedField field = fields[i];
          com.debughelper.tools.r8.graph.DexValue staticValue = field.getStaticValue();
          assert staticValue != null;
          values.add(staticValue);
          if (!staticValue.isDefault(field.field.type)) {
            length = i + 1;
          }
        }
        if (length > 0) {
          staticValues = new com.debughelper.tools.r8.graph.DexEncodedArray(
              values.subList(0, length).toArray(new DexValue[length]));
        } else {
          staticValues = null;
        }
      }
    }
  }

  public DexEncodedArray getStaticValues() {
    // The sentinel value is left over for classes that actually have no fields.
    if (staticValues == SENTINEL_NOT_YET_COMPUTED) {
      assert !hasMethodsOrFields();
      return null;
    }
    return staticValues;
  }

  public void addMethod(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    if (method.accessFlags.isStatic()
        || method.accessFlags.isPrivate()
        || method.accessFlags.isConstructor()) {
      addDirectMethod(method);
    } else {
      addVirtualMethod(method);
    }
  }

  public void addVirtualMethod(com.debughelper.tools.r8.graph.DexEncodedMethod virtualMethod) {
    assert !virtualMethod.accessFlags.isStatic();
    assert !virtualMethod.accessFlags.isPrivate();
    assert !virtualMethod.accessFlags.isConstructor();
    synchronized (virtualMethods) {
      virtualMethods = Arrays.copyOf(virtualMethods, virtualMethods.length + 1);
      virtualMethods[virtualMethods.length - 1] = virtualMethod;
    }
  }

  public void addDirectMethod(com.debughelper.tools.r8.graph.DexEncodedMethod staticMethod) {
    assert staticMethod.accessFlags.isStatic() || staticMethod.accessFlags.isPrivate()
        || staticMethod.accessFlags.isConstructor();
    synchronized (directMethods) {
      directMethods = Arrays.copyOf(directMethods, directMethods.length + 1);
      directMethods[directMethods.length - 1] = staticMethod;
    }
  }

  public void sortMembers() {
    sortEncodedFields(staticFields);
    sortEncodedFields(instanceFields);
    sortEncodedMethods(directMethods);
    sortEncodedMethods(virtualMethods);
  }

  private void sortEncodedFields(DexEncodedField[] fields) {
    synchronized (fields) {
      Arrays.sort(fields, Comparator.comparing(a -> a.field));
    }
  }

  private void sortEncodedMethods(DexEncodedMethod[] methods) {
    synchronized (methods) {
      Arrays.sort(methods, Comparator.comparing(a -> a.method));
    }
  }

  @Override
  public DexProgramClass get() {
    return this;
  }

  public void setClassFileVersion(int classFileVersion) {
    assert classFileVersion >= 0;
    this.classFileVersion = classFileVersion;
  }

  public boolean hasClassFileVersion() {
    return classFileVersion >= 0;
  }

  public int getClassFileVersion() {
    assert classFileVersion != -1;
    return classFileVersion;
  }
}
