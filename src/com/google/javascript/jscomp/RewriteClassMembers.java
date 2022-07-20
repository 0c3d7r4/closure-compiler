/*
 * Copyright 2021 The Closure Compiler Authors.
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

import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.Node;

/** Replaces the ES2022 class fields and class static blocks with constructor declaration. */
public final class RewriteClassMembers implements NodeTraversal.Callback, CompilerPass {

  private final AbstractCompiler compiler;

  public RewriteClassMembers(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);
    TranspilationPasses.maybeMarkFeaturesAsTranspiledAway(
        compiler, Feature.PUBLIC_CLASS_FIELDS, Feature.CLASS_STATIC_BLOCK);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (n.isScript()) {
      FeatureSet scriptFeatures = NodeUtil.getFeatureSetOfScript(n);
      return scriptFeatures == null
          || scriptFeatures.contains(Feature.PUBLIC_CLASS_FIELDS)
          || scriptFeatures.contains(Feature.CLASS_STATIC_BLOCK);
    }
    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isClass()) {
      visitClass(t, n);
    }
  }

  /** Transpile the actual class members themselves */
  private void visitClass(NodeTraversal t, Node classNode) {
    if (!NodeUtil.isClassDeclaration(classNode)) {
      t.report(classNode, Es6ToEs3Util.CANNOT_CONVERT_YET, "Not a class declaration");
      return;
    }
    Node staticInsertionPoint = classNode;
    Node classMembers = classNode.getLastChild();
    Node next;
    for (Node member = classMembers.getFirstChild(); member != null; member = next) {
      next = member.getNext();
      // this next is necessary because we directly move static blocks so we will get the incorrect
      // element if just update member in the loop guard
      if (member.isMemberFieldDef() || member.isComputedFieldDef()) {
        t.report(member, Es6ToEs3Util.CANNOT_CONVERT_YET, "Public class fields");
      } else if (NodeUtil.isClassStaticBlock(member)) {
        staticInsertionPoint = visitClassStaticBlock(t, member, staticInsertionPoint);
      }
    }
  }

  /** Handles the transpilation of class static blocks */
  private Node visitClassStaticBlock(NodeTraversal t, Node staticBlock, Node insertionPoint) {
    // TODO(b/235871861): replace all this and super inside the static block
    if (NodeUtil.referencesEnclosingReceiver(staticBlock)) {
      t.report(staticBlock, Es6ToEs3Util.CANNOT_CONVERT_YET, "This or super in static block");
      return insertionPoint;
    }
    if (!NodeUtil.getVarsDeclaredInBranch(staticBlock).isEmpty()) {
      t.report(staticBlock, Es6ToEs3Util.CANNOT_CONVERT_YET, "Var in static block");
      return insertionPoint;
    }
    staticBlock.detach();
    staticBlock.insertAfter(insertionPoint);
    t.reportCodeChange();
    return staticBlock;
  }
}
