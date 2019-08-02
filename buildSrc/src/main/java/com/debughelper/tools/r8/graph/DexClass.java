// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.Descriptor;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexClasspathClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.kotlin.KotlinInfo;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.ThrowingConsumer;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterators;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public abstract class DexClass extends com.debughelper.tools.r8.graph.DexItem {

  private static final com.debughelper.tools.r8.graph.DexEncodedMethod[] NO_METHODS = {};
  private static final com.debughelper.tools.r8.graph.DexEncodedField[] NO_FIELDS = {};

  public final com.debughelper.tools.r8.origin.Origin origin;
  public com.debughelper.tools.r8.graph.DexType type;
  public final com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags;
  public com.debughelper.tools.r8.graph.DexType superType;
  public com.debughelper.tools.r8.graph.DexTypeList interfaces;
  public DexString sourceFile;

  /**
   * Access has to be synchronized during concurrent collection/writing phase.
   */
  protected com.debughelper.tools.r8.graph.DexEncodedField[] staticFields;
  /**
   * Access has to be synchronized during concurrent collection/writing phase.
   */
  protected com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields;
  /**
   * Access has to be synchronized during concurrent collection/writing phase.
   */
  protected com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods;
  /**
   * Access has to be synchronized during concurrent collection/writing phase.
   */
  protected com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods;

  /** Enclosing context of this class if it is an inner class, null otherwise. */
  private com.debughelper.tools.r8.graph.EnclosingMethodAttribute enclosingMethod;

  /** InnerClasses table. If this class is an inner class, it will have an entry here. */
  private final List<InnerClassAttribute> innerClasses;

  public com.debughelper.tools.r8.graph.DexAnnotationSet annotations;

  public DexClass(
      DexString sourceFile,
      DexTypeList interfaces,
      ClassAccessFlags accessFlags,
      com.debughelper.tools.r8.graph.DexType superType,
      com.debughelper.tools.r8.graph.DexType type,
      com.debughelper.tools.r8.graph.DexEncodedField[] staticFields,
      com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods,
      com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods,
      com.debughelper.tools.r8.graph.EnclosingMethodAttribute enclosingMethod,
      List<InnerClassAttribute> innerClasses,
      DexAnnotationSet annotations,
      com.debughelper.tools.r8.origin.Origin origin,
      boolean skipNameValidationForTesting) {
    assert origin != null;
    this.origin = origin;
    this.sourceFile = sourceFile;
    this.interfaces = interfaces;
    this.accessFlags = accessFlags;
    this.superType = superType;
    this.type = type;
    setStaticFields(staticFields);
    setInstanceFields(instanceFields);
    setDirectMethods(directMethods);
    setVirtualMethods(virtualMethods);
    this.enclosingMethod = enclosingMethod;
    this.innerClasses = innerClasses;
    this.annotations = annotations;
    if (type == superType) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Class " + type.toString() + " cannot extend itself");
    }
    for (com.debughelper.tools.r8.graph.DexType interfaceType : interfaces.values) {
      if (type == interfaceType) {
        throw new com.debughelper.tools.r8.errors.CompilationError("Interface " + type.toString() + " cannot implement itself");
      }
    }
    if (!skipNameValidationForTesting && !type.descriptor.isValidClassDescriptor()) {
      throw new CompilationError(
          "Class descriptor '"
              + type.descriptor.toString()
              + "' cannot be represented in dex format.");
    }
  }

  public Iterable<com.debughelper.tools.r8.graph.DexEncodedField> fields() {
    return () ->
        Iterators.concat(Iterators.forArray(instanceFields), Iterators.forArray(staticFields));
  }

  public Iterable<com.debughelper.tools.r8.graph.DexEncodedMethod> methods() {
    return () ->
        Iterators.concat(Iterators.forArray(directMethods), Iterators.forArray(virtualMethods));
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    throw new Unreachable();
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods() {
    return directMethods;
  }

  public void setDirectMethods(com.debughelper.tools.r8.graph.DexEncodedMethod[] values) {
    directMethods = MoreObjects.firstNonNull(values, NO_METHODS);
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod[] virtualMethods() {
    return virtualMethods;
  }

  public void setVirtualMethods(com.debughelper.tools.r8.graph.DexEncodedMethod[] values) {
    virtualMethods = MoreObjects.firstNonNull(values, NO_METHODS);
  }

  public void forEachMethod(Consumer<com.debughelper.tools.r8.graph.DexEncodedMethod> consumer) {
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : directMethods()) {
      consumer.accept(method);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : virtualMethods()) {
      consumer.accept(method);
    }
  }

  public <E extends Throwable> void forEachMethodThrowing(
      ThrowingConsumer<com.debughelper.tools.r8.graph.DexEncodedMethod, E> consumer) throws E {
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : directMethods()) {
      consumer.accept(method);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : virtualMethods()) {
      consumer.accept(method);
    }
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod[] allMethodsSorted() {
    int vLen = virtualMethods.length;
    int dLen = directMethods.length;
    com.debughelper.tools.r8.graph.DexEncodedMethod[] result = new com.debughelper.tools.r8.graph.DexEncodedMethod[vLen + dLen];
    System.arraycopy(virtualMethods, 0, result, 0, vLen);
    System.arraycopy(directMethods, 0, result, vLen, dLen);
    Arrays.sort(result,
        (com.debughelper.tools.r8.graph.DexEncodedMethod a, com.debughelper.tools.r8.graph.DexEncodedMethod b) -> a.method.slowCompareTo(b.method));
    return result;
  }

  /**
   * For all annotations on the class and all annotations on its methods and fields apply the
   * specified consumer.
   */
  public void forEachAnnotation(Consumer<com.debughelper.tools.r8.graph.DexAnnotation> consumer) {
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotations.annotations) {
      consumer.accept(annotation);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : directMethods()) {
      for (com.debughelper.tools.r8.graph.DexAnnotation annotation : method.annotations.annotations) {
        consumer.accept(annotation);
      }
      method.parameterAnnotationsList.forEachAnnotation(consumer);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : virtualMethods()) {
      for (com.debughelper.tools.r8.graph.DexAnnotation annotation : method.annotations.annotations) {
        consumer.accept(annotation);
      }
      method.parameterAnnotationsList.forEachAnnotation(consumer);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedField field : instanceFields()) {
      for (com.debughelper.tools.r8.graph.DexAnnotation annotation : field.annotations.annotations) {
        consumer.accept(annotation);
      }
    }
    for (com.debughelper.tools.r8.graph.DexEncodedField field : staticFields()) {
      for (DexAnnotation annotation : field.annotations.annotations) {
        consumer.accept(annotation);
      }
    }
  }

  public void forEachField(Consumer<com.debughelper.tools.r8.graph.DexEncodedField> consumer) {
    for (com.debughelper.tools.r8.graph.DexEncodedField field : staticFields()) {
      consumer.accept(field);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedField field : instanceFields()) {
      consumer.accept(field);
    }
  }

  public com.debughelper.tools.r8.graph.DexEncodedField[] staticFields() {
    return staticFields;
  }

  public void setStaticFields(com.debughelper.tools.r8.graph.DexEncodedField[] values) {
    staticFields = MoreObjects.firstNonNull(values, NO_FIELDS);
  }

  public boolean definesStaticField(com.debughelper.tools.r8.graph.DexField field) {
    for (com.debughelper.tools.r8.graph.DexEncodedField encodedField : staticFields()) {
      if (encodedField.field == field) {
        return true;
      }
    }
    return false;
  }

  public com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields() {
    return instanceFields;
  }

  public void setInstanceFields(com.debughelper.tools.r8.graph.DexEncodedField[] values) {
    instanceFields = MoreObjects.firstNonNull(values, NO_FIELDS);
  }

  public com.debughelper.tools.r8.graph.DexEncodedField[] allFieldsSorted() {
    int iLen = instanceFields.length;
    int sLen = staticFields.length;
    com.debughelper.tools.r8.graph.DexEncodedField[] result = new com.debughelper.tools.r8.graph.DexEncodedField[iLen + sLen];
    System.arraycopy(instanceFields, 0, result, 0, iLen);
    System.arraycopy(staticFields, 0, result, iLen, sLen);
    Arrays.sort(result,
        (com.debughelper.tools.r8.graph.DexEncodedField a, com.debughelper.tools.r8.graph.DexEncodedField b) -> a.field.slowCompareTo(b.field));
    return result;
  }

  /**
   * Find static field in this class matching field
   */
  public com.debughelper.tools.r8.graph.DexEncodedField lookupStaticField(com.debughelper.tools.r8.graph.DexField field) {
    return lookupTarget(staticFields(), field);
  }

  /**
   * Find instance field in this class matching field.
   */
  public com.debughelper.tools.r8.graph.DexEncodedField lookupInstanceField(com.debughelper.tools.r8.graph.DexField field) {
    return lookupTarget(instanceFields(), field);
  }

  /**
   * Find field in this class matching field.
   */
  public com.debughelper.tools.r8.graph.DexEncodedField lookupField(DexField field) {
    DexEncodedField result = lookupInstanceField(field);
    return result == null ? lookupStaticField(field) : result;
  }

  /**
   * Find direct method in this class matching method.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupDirectMethod(com.debughelper.tools.r8.graph.DexMethod method) {
    return lookupTarget(directMethods(), method);
  }

  /**
   * Find virtual method in this class matching method.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupVirtualMethod(com.debughelper.tools.r8.graph.DexMethod method) {
    return lookupTarget(virtualMethods(), method);
  }

  /**
   * Find method in this class matching method.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupMethod(DexMethod method) {
    com.debughelper.tools.r8.graph.DexEncodedMethod result = lookupDirectMethod(method);
    return result == null ? lookupVirtualMethod(method) : result;
  }

  private <T extends DexItem, S extends Descriptor<T, S>> T lookupTarget(T[] items, S descriptor) {
    for (T entry : items) {
      if (descriptor.match(entry)) {
        return entry;
      }
    }
    return null;
  }

  // Tells whether this is an interface.
  public boolean isInterface() {
    return accessFlags.isInterface();
  }

  public abstract void addDependencies(MixedSectionCollection collector);

  public boolean isProgramClass() {
    return false;
  }

  public DexProgramClass asProgramClass() {
    return null;
  }

  public boolean isClasspathClass() {
    return false;
  }

  public DexClasspathClass asClasspathClass() {
    return null;
  }

  public boolean isLibraryClass() {
    return false;
  }

  public DexLibraryClass asLibraryClass() {
    return null;
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod getClassInitializer() {
    return Arrays.stream(directMethods()).filter(com.debughelper.tools.r8.graph.DexEncodedMethod::isClassInitializer).findAny()
        .orElse(null);
  }

  public Origin getOrigin() {
    return this.origin;
  }

  public DexType getType() {
    return this.type;
  }

  public boolean hasClassInitializer() {
    return getClassInitializer() != null;
  }

  public boolean hasTrivialClassInitializer() {
    if (isLibraryClass()) {
      // We don't know for library classes in general but assume that java.lang.Object is safe.
      return superType == null;
    }
    com.debughelper.tools.r8.graph.DexEncodedMethod clinit = getClassInitializer();
    return clinit != null && clinit.getCode() != null && clinit.getCode().isEmptyVoidMethod();
  }

  public boolean hasNonTrivialClassInitializer() {
    if (isLibraryClass()) {
      // We don't know for library classes in general but assume that java.lang.Object is safe.
      return superType != null;
    }
    com.debughelper.tools.r8.graph.DexEncodedMethod clinit = getClassInitializer();
    if (clinit == null || clinit.getCode() == null) {
      return false;
    }
    return !clinit.getCode().isEmptyVoidMethod();
  }

  public boolean hasDefaultInitializer() {
    return getDefaultInitializer() != null;
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod getDefaultInitializer() {
    for (DexEncodedMethod method : directMethods()) {
      if (method.isDefaultInitializer()) {
        return method;
      }
    }
    return null;
  }

  public boolean defaultValuesForStaticFieldsMayTriggerAllocation() {
    return Arrays.stream(staticFields())
        .anyMatch(field -> !field.getStaticValue().mayTriggerAllocation());
  }

  public List<InnerClassAttribute> getInnerClasses() {
    return innerClasses;
  }

  public EnclosingMethodAttribute getEnclosingMethod() {
    return enclosingMethod;
  }

  public void clearEnclosingMethod() {
    enclosingMethod = null;
  }

  public void clearInnerClasses() {
    innerClasses.clear();
  }

  /** Returns kotlin class info if the class is synthesized by kotlin compiler. */
  public abstract KotlinInfo getKotlinInfo();

  public final boolean hasKotlinInfo() {
    return getKotlinInfo() != null;
  }
}
