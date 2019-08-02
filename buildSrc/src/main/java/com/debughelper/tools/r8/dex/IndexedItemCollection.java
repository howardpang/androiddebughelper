// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.IndexedDexItem;

/**
 * Common interface for constant pools.
 *
 * <p>This is semantically a wrapper around a number of sets for all subtypes of
 * {@link com.debughelper.tools.r8.graph.IndexedDexItem}. <b>Items should not be added directly to this collection.</b> Instead see
 * {@link DexItem#collectIndexedItems}.
 *
 * <p>Note that the various add methods of this class are not transitive, i.e., they do not add
 * components of the {@link com.debughelper.tools.r8.graph.IndexedDexItem} itself. Use a call to
 * {@link IndexedDexItem#collectIndexedItems} for this.
 */
public interface IndexedItemCollection {

  /**
   * Adds the given class to the collection.
   *
   * <p>Does not add the class' components.
   *
   * @return true if the class was not in the pool before.
   */
  boolean addClass(DexProgramClass dexProgramClass);

  /**
   * Adds the given field to the collection.
   *
   * <p>Does not add the field's components.
   *
   * @return true if the field was not in the pool before.
   */
  boolean addField(com.debughelper.tools.r8.graph.DexField field);

  /**
   * Adds the given method to the collection.
   *
   * <p>Does not add the method's components.
   *
   * @return true if the method was not in the pool before.
   */
  boolean addMethod(com.debughelper.tools.r8.graph.DexMethod method);

  /**
   * Adds the given string to the collection.
   *
   * <p>Does not add the string's components.
   *
   * @return true if the string was not in the pool before.
   */
  boolean addString(com.debughelper.tools.r8.graph.DexString string);

  /**
   * Adds the given proto to the collection.
   *
   * <p>Does not add the proto's components.
   *
   * @return true if the proto was not in the pool before.
   */
  boolean addProto(DexProto proto);

  /**
   * Adds the given type to the collection.
   *
   * <p>Does not add the type's components.
   *
   * @return true if the type was not in the pool before.
   */
  boolean addType(com.debughelper.tools.r8.graph.DexType type);

  /**
   * Adds the given call site to the collection.
   *
   * <p>Does not add the call site's components.
   *
   * @return true if the call site was not in the pool before.
   */
  boolean addCallSite(DexCallSite callSite);

  /**
   * Adds the given method handle to the collection.
   *
   * <p>Does not add the method handle site's components.
   *
   * @return true if the method handle was not in the pool before.
   */
  boolean addMethodHandle(DexMethodHandle methodHandle);

  default com.debughelper.tools.r8.graph.DexString getRenamedName(DexMethod method) {
    return method.name;
  }

  default com.debughelper.tools.r8.graph.DexString getRenamedName(DexField field) {
    return field.name;
  }

  default DexString getRenamedDescriptor(DexType type) {
    return type.descriptor;
  }
}
