// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

import com.debughelper.tools.r8.ir.analysis.type.Bottom;
import com.debughelper.tools.r8.ir.analysis.type.NullLatticeElement;
import com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement;
import com.debughelper.tools.r8.ir.analysis.type.Top;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.function.BinaryOperator;
import java.util.stream.Stream;

/**
 * The base abstraction of lattice elements for local type analysis.
 */
abstract public class TypeLatticeElement {
  private final boolean isNullable;

  TypeLatticeElement(boolean isNullable) {
    this.isNullable = isNullable;
  }

  public boolean isNullable() {
    return isNullable;
  }

  public boolean mustBeNull() {
    return false;
  }

  /**
   * Defines how to join with null or switch to nullable lattice element.
   *
   * @return {@link TypeLatticeElement} a result of joining with null.
   */
  abstract TypeLatticeElement asNullable();

  /**
   * Defines how to switch to non-nullable lattice element.
   *
   * @return {@link TypeLatticeElement} a similar lattice element with nullable flag flipped.
   */
  public TypeLatticeElement asNonNullable() {
    throw new com.debughelper.tools.r8.errors.Unreachable("Flipping nullable is not allowed in general.");
  }

  String isNullableString() {
    return isNullable() ? "" : "@NonNull ";
  }

  /**
   * Computes the least upper bound of the current and the other elements.
   *
   * @param appInfo {@link com.debughelper.tools.r8.graph.AppInfo}.
   * @param l1 {@link TypeLatticeElement} to join.
   * @param l2 {@link TypeLatticeElement} to join.
   * @return {@link TypeLatticeElement}, a least upper bound of {@param l1} and {@param l2}.
   */
  public static TypeLatticeElement join(
          com.debughelper.tools.r8.graph.AppInfo appInfo, TypeLatticeElement l1, TypeLatticeElement l2) {
    if (l1.isBottom()) {
      return l2;
    }
    if (l2.isBottom()) {
      return l1;
    }
    if (l1.isTop() || l2.isTop()) {
      return com.debughelper.tools.r8.ir.analysis.type.Top.getInstance();
    }
    if (l1 instanceof com.debughelper.tools.r8.ir.analysis.type.NullLatticeElement) {
      return l2.asNullable();
    }
    if (l2 instanceof com.debughelper.tools.r8.ir.analysis.type.NullLatticeElement) {
      return l1.asNullable();
    }
    if (l1 instanceof com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement) {
      return l2 instanceof com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement ? l1 : com.debughelper.tools.r8.ir.analysis.type.Top.getInstance();
    }
    if (l2 instanceof com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement) {
      // By the above case !(l1 instanceof PrimitiveTypeLatticeElement)
      return com.debughelper.tools.r8.ir.analysis.type.Top.getInstance();
    }
    // From now on, l1 and l2 are reference types, i.e., either ArrayType or ClassType.
    boolean isNullable = l1.isNullable() || l2.isNullable();
    if (l1.getClass() != l2.getClass()) {
      return objectType(appInfo, isNullable);
    }
    // From now on, l1.getClass() == l2.getClass()
    if (l1.isArrayTypeLatticeElement()) {
      ArrayTypeLatticeElement a1 = l1.asArrayTypeLatticeElement();
      ArrayTypeLatticeElement a2 = l2.asArrayTypeLatticeElement();
      // Identical types are the same elements
      if (a1.getArrayType() == a2.getArrayType()) {
        return a1.isNullable() ? a1 : a2;
      }
      // If non-equal, find the inner-most reference types for each.
      com.debughelper.tools.r8.graph.DexType a1BaseReferenceType = a1.getArrayBaseType(appInfo.dexItemFactory);
      int a1Nesting = a1.getNesting();
      if (a1BaseReferenceType.isPrimitiveType()) {
        a1Nesting--;
        a1BaseReferenceType = appInfo.dexItemFactory.objectType;
      }
      com.debughelper.tools.r8.graph.DexType a2BaseReferenceType = a2.getArrayBaseType(appInfo.dexItemFactory);
      int a2Nesting = a2.getNesting();
      if (a2BaseReferenceType.isPrimitiveType()) {
        a2Nesting--;
        a2BaseReferenceType = appInfo.dexItemFactory.objectType;
      }
      assert a1BaseReferenceType.isClassType() && a2BaseReferenceType.isClassType();
      // If any nestings hit zero object is the join.
      if (a1Nesting == 0 || a2Nesting == 0) {
        return objectType(appInfo, isNullable);
      }
      // If the nestings differ the join is the smallest nesting level.
      if (a1Nesting != a2Nesting) {
        int min = Math.min(a1Nesting, a2Nesting);
        return objectArrayType(appInfo, min, isNullable);
      }
      // For different class element types, compute the least upper bound of element types.
      com.debughelper.tools.r8.graph.DexType lub = a1BaseReferenceType.computeLeastUpperBound(appInfo, a2BaseReferenceType);
      // Create the full array type.
      com.debughelper.tools.r8.graph.DexType arrayTypeLub = appInfo.dexItemFactory.createArrayType(a1Nesting, lub);
      return new ArrayTypeLatticeElement(arrayTypeLub, isNullable);
    }
    if (l1.isClassTypeLatticeElement()) {
      ClassTypeLatticeElement c1 = l1.asClassTypeLatticeElement();
      ClassTypeLatticeElement c2 = l2.asClassTypeLatticeElement();
      if (c1.getClassType() == c2.getClassType()) {
        return c1.isNullable() ? c1 : c2;
      } else {
        com.debughelper.tools.r8.graph.DexType lub = c1.getClassType().computeLeastUpperBound(appInfo, c2.getClassType());
        return new ClassTypeLatticeElement(lub, isNullable);
      }
    }
    throw new Unreachable("unless a new type lattice is introduced.");
  }

  static BinaryOperator<TypeLatticeElement> joiner(com.debughelper.tools.r8.graph.AppInfo appInfo) {
    return (l1, l2) -> join(appInfo, l1, l2);
  }

  public static TypeLatticeElement join(com.debughelper.tools.r8.graph.AppInfo appInfo, Stream<TypeLatticeElement> types) {
    BinaryOperator<TypeLatticeElement> joiner = joiner(appInfo);
    return types.reduce(Bottom.getInstance(), joiner, joiner);
  }

  /**
   * Determines the strict partial order of the given {@link TypeLatticeElement}s.
   *
   * @param appInfo {@link com.debughelper.tools.r8.graph.AppInfo} to compute the least upper bound of {@link TypeLatticeElement}
   * @param l1 subject {@link TypeLatticeElement}
   * @param l2 expected to be *strict* bigger than {@param l1}
   * @return {@code true} if {@param l1} is strictly less than {@param l2}.
   */
  public static boolean strictlyLessThan(
          com.debughelper.tools.r8.graph.AppInfo appInfo, TypeLatticeElement l1, TypeLatticeElement l2) {
    if (l1.equals(l2)) {
      return false;
    }
    TypeLatticeElement lub = join(appInfo, Stream.of(l1, l2));
    return !l1.equals(lub) && l2.equals(lub);
  }

  /**
   * Determines the partial order of the given {@link TypeLatticeElement}s.
   *
   * @param appInfo {@link com.debughelper.tools.r8.graph.AppInfo} to compute the least upper bound of {@link TypeLatticeElement}
   * @param l1 subject {@link TypeLatticeElement}
   * @param l2 expected to be bigger than or equal to {@param l1}
   * @return {@code true} if {@param l1} is less than or equal to {@param l2}.
   */
  public static boolean lessThanOrEqual(
          com.debughelper.tools.r8.graph.AppInfo appInfo, TypeLatticeElement l1, TypeLatticeElement l2) {
    return l1.equals(l2) || strictlyLessThan(appInfo, l1, l2);
  }

  /**
   * Represents a type that can be everything.
   *
   * @return {@code true} if the corresponding {@link com.debughelper.tools.r8.ir.code.Value} could be any kinds.
   */
  public boolean isTop() {
    return false;
  }

  /**
   * Represents an empty type.
   *
   * @return {@code true} if the type of corresponding {@link Value} is not determined yet.
   */
  boolean isBottom() {
    return false;
  }

  public boolean isArrayTypeLatticeElement() {
    return false;
  }

  public ArrayTypeLatticeElement asArrayTypeLatticeElement() {
    return null;
  }

  public boolean isClassTypeLatticeElement() {
    return false;
  }

  public ClassTypeLatticeElement asClassTypeLatticeElement() {
    return null;
  }

  public boolean isPrimitive() {
    return false;
  }

  static ClassTypeLatticeElement objectType(com.debughelper.tools.r8.graph.AppInfo appInfo, boolean isNullable) {
    return new ClassTypeLatticeElement(appInfo.dexItemFactory.objectType, isNullable);
  }

  static ArrayTypeLatticeElement objectArrayType(com.debughelper.tools.r8.graph.AppInfo appInfo, int nesting, boolean isNullable) {
    return new ArrayTypeLatticeElement(
        appInfo.dexItemFactory.createArrayType(nesting, appInfo.dexItemFactory.objectType),
        isNullable);
  }

  public static TypeLatticeElement fromDexType(com.debughelper.tools.r8.graph.DexType type, boolean isNullable) {
    if (type == DexItemFactory.nullValueType) {
      return NullLatticeElement.getInstance();
    }
    if (type.isPrimitiveType()) {
      return PrimitiveTypeLatticeElement.getInstance();
    }
    if (type.isClassType()) {
      return new ClassTypeLatticeElement(type, isNullable);
    }
    assert type.isArrayType();
    return new ArrayTypeLatticeElement(type, isNullable);
  }

  public static TypeLatticeElement newArray(com.debughelper.tools.r8.graph.DexType arrayType, boolean isNullable) {
    return new ArrayTypeLatticeElement(arrayType, isNullable);
  }

  public TypeLatticeElement arrayGet(com.debughelper.tools.r8.graph.AppInfo appInfo) {
    return Top.getInstance();
  }

  public TypeLatticeElement checkCast(AppInfo appInfo, DexType castType) {
    TypeLatticeElement castTypeLattice = fromDexType(castType, isNullable());
    // Special case: casting null.
    if (mustBeNull()) {
      return castTypeLattice;
    }
    if (lessThanOrEqual(appInfo, this, castTypeLattice)) {
      return this;
    }
    return castTypeLattice;
  }

  @Override
  abstract public String toString();

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || o.getClass() != this.getClass()) {
      return false;
    }
    TypeLatticeElement otherElement = (TypeLatticeElement) o;
    return otherElement.isNullable() == isNullable;
  }

  @Override
  public int hashCode() {
    return isNullable ? 1 : 0;
  }
}
