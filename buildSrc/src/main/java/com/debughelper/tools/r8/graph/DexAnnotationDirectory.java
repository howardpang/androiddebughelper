// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.KeyedDexItem;
import com.debughelper.tools.r8.graph.PresortedComparable;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.utils.OrderedMergingIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class DexAnnotationDirectory extends DexItem {

  private final com.debughelper.tools.r8.graph.DexProgramClass clazz;
  private final List<com.debughelper.tools.r8.graph.DexEncodedMethod> methodAnnotations;
  private final List<com.debughelper.tools.r8.graph.DexEncodedMethod> parameterAnnotations;
  private final List<com.debughelper.tools.r8.graph.DexEncodedField> fieldAnnotations;
  private final boolean classHasOnlyInternalizableAnnotations;

  public DexAnnotationDirectory(DexProgramClass clazz) {
    this.clazz = clazz;
    this.classHasOnlyInternalizableAnnotations = clazz.hasOnlyInternalizableAnnotations();
    assert isSorted(clazz.directMethods());
    assert isSorted(clazz.virtualMethods());
    com.debughelper.tools.r8.utils.OrderedMergingIterator<com.debughelper.tools.r8.graph.DexEncodedMethod, DexMethod> methods =
        new com.debughelper.tools.r8.utils.OrderedMergingIterator<>(clazz.directMethods(), clazz.virtualMethods());
    methodAnnotations = new ArrayList<>();
    parameterAnnotations = new ArrayList<>();
    while (methods.hasNext()) {
      com.debughelper.tools.r8.graph.DexEncodedMethod method = methods.next();
      if (!method.annotations.isEmpty()) {
        methodAnnotations.add(method);
      }
      if (!method.parameterAnnotationsList.isEmpty()) {
        parameterAnnotations.add(method);
      }
    }
    assert isSorted(clazz.staticFields());
    assert isSorted(clazz.instanceFields());
    com.debughelper.tools.r8.utils.OrderedMergingIterator<com.debughelper.tools.r8.graph.DexEncodedField, DexField> fields =
        new OrderedMergingIterator<>(clazz.staticFields(), clazz.instanceFields());
    fieldAnnotations = new ArrayList<>();
    while (fields.hasNext()) {
      com.debughelper.tools.r8.graph.DexEncodedField field = fields.next();
      if (!field.annotations.isEmpty()) {
        fieldAnnotations.add(field);
      }
    }
  }

  public DexAnnotationSet getClazzAnnotations() {
    return clazz.annotations;
  }

  public List<com.debughelper.tools.r8.graph.DexEncodedMethod> getMethodAnnotations() {
    return methodAnnotations;
  }

  public List<DexEncodedMethod> getParameterAnnotations() {
    return parameterAnnotations;
  }

  public List<DexEncodedField> getFieldAnnotations() {
    return fieldAnnotations;
  }


  /**
   * DexAnnotationDirectory of a class can be canonicalized only if a clazz has annotations and
   * does not contains annotations for his fields, methods or parameters. Indeed, if a field, method
   * or parameter has annotations in this case, the DexAnnotationDirectory can not be shared since
   * it will contains information about field, method and parameters that are only related to only
   * one class.
   */
  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof DexAnnotationDirectory)) {
      return false;
    }
    if (classHasOnlyInternalizableAnnotations) {
      DexAnnotationDirectory other = (DexAnnotationDirectory) obj;
      if (!other.clazz.hasOnlyInternalizableAnnotations()) {
        return false;
      }
      return clazz.annotations.equals(other.clazz.annotations);
    }
    return super.equals(obj);
  }

  @Override
  public final int hashCode() {
    if (classHasOnlyInternalizableAnnotations) {
      return clazz.annotations.hashCode();
    }
    return super.hashCode();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection,
      DexMethod method, int instructionOffset) {
    throw new com.debughelper.tools.r8.errors.Unreachable();
  }

  @Override
  public void collectMixedSectionItems(MixedSectionCollection collection) {
    throw new Unreachable();
  }

  private static <T extends PresortedComparable<T>> boolean isSorted(com.debughelper.tools.r8.graph.KeyedDexItem<T>[] items) {
    return isSorted(items, KeyedDexItem::getKey);
  }

  private static <S, T extends Comparable<T>> boolean isSorted(S[] items, Function<S, T> getter) {
    T current = null;
    for (S item : items) {
      T next = getter.apply(item);
      if (current != null && current.compareTo(next) >= 0) {
        return false;
      }
      current = next;
    }
    return true;
  }
}
