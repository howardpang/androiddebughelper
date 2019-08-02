// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import com.debughelper.tools.r8.DataEntryResource;
import com.debughelper.tools.r8.KeepForSubclassing;
import com.debughelper.tools.r8.ResourceException;

@com.debughelper.tools.r8.KeepForSubclassing
public interface DataResourceProvider {

  @KeepForSubclassing
  interface Visitor {
    void visit(DataDirectoryResource directory);
    void visit(DataEntryResource file);
  }

  void accept(Visitor visitor) throws ResourceException;

}
