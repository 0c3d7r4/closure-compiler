/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.javascript.rhino.Node;

/** Container class that holds information about the JS code a JsMessage was built from. */
public final class JsMessageDefinition {

  private final Node messageNode;

  /**
   * Constructs JS message definition.
   *
   * @param messageNode The `goog.getMsg()` call node.
   */
  JsMessageDefinition(Node messageNode) {
    this.messageNode = messageNode;
  }

  public Node getMessageNode() {
    return messageNode;
  }
}
