/*
 * Copyright (c) 2013, 2014 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.translator;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.joni.Regex;
import org.jruby.ast.*;
import org.jruby.common.IRubyWarnings;
import org.jruby.lexer.yacc.InvalidSourcePosition;
import org.jruby.truffle.nodes.*;
import org.jruby.truffle.nodes.DefinedNode;
import org.jruby.truffle.nodes.ForNode;
import org.jruby.truffle.nodes.cast.*;
import org.jruby.truffle.nodes.cast.LambdaNode;
import org.jruby.truffle.nodes.control.*;
import org.jruby.truffle.nodes.control.AndNode;
import org.jruby.truffle.nodes.control.BreakNode;
import org.jruby.truffle.nodes.control.EnsureNode;
import org.jruby.truffle.nodes.control.IfNode;
import org.jruby.truffle.nodes.control.NextNode;
import org.jruby.truffle.nodes.control.OrNode;
import org.jruby.truffle.nodes.control.RedoNode;
import org.jruby.truffle.nodes.control.RescueNode;
import org.jruby.truffle.nodes.control.RetryNode;
import org.jruby.truffle.nodes.control.ReturnNode;
import org.jruby.truffle.nodes.control.WhileNode;
import org.jruby.truffle.nodes.core.*;
import org.jruby.truffle.nodes.globals.CheckMatchVariableTypeNode;
import org.jruby.truffle.nodes.globals.WriteReadOnlyGlobalNode;
import org.jruby.truffle.nodes.literal.*;
import org.jruby.truffle.nodes.literal.ArrayLiteralNode;
import org.jruby.truffle.nodes.methods.*;
import org.jruby.truffle.nodes.methods.UndefNode;
import org.jruby.truffle.nodes.methods.locals.*;
import org.jruby.truffle.nodes.objects.*;
import org.jruby.truffle.nodes.objects.SelfNode;
import org.jruby.truffle.nodes.yield.YieldNode;
import org.jruby.truffle.runtime.*;
import org.jruby.truffle.runtime.LexicalScope;
import org.jruby.truffle.runtime.core.*;
import org.jruby.truffle.runtime.methods.*;
import org.jruby.util.KeyValuePair;
import org.jruby.util.cli.Options;

import java.util.*;

/**
 * A JRuby parser node visitor which translates JRuby AST nodes into truffle Nodes. Therefore there is some namespace
 * contention here! We make all references to JRuby explicit.
 */
public class BodyTranslator extends Translator {

    protected final BodyTranslator parent;

    protected final TranslatorEnvironment environment;

    public boolean translatingForStatement = false;
    public boolean useClassVariablesAsIfInClass = false;
    private boolean translatingNextExpression = false;
    private String currentCallMethodName = null;

    private static final Set<String> debugIgnoredCalls = new HashSet<>();

    static {
        debugIgnoredCalls.add("downto");
        debugIgnoredCalls.add("each");
        debugIgnoredCalls.add("times");
        debugIgnoredCalls.add("upto");
    }

    public static final Set<String> FRAME_LOCAL_GLOBAL_VARIABLES = new HashSet<>(Arrays.asList("$~", "$+", "$&", "$`", "$'", "$1", "$2", "$3", "$4", "$5", "$6", "$7", "$8", "$9"));
    public static final Set<String> THREAD_LOCAL_GLOBAL_VARIABLES = new HashSet<>(Arrays.asList("$_"));

    public BodyTranslator(RubyNode currentNode, RubyContext context, BodyTranslator parent, TranslatorEnvironment environment, Source source) {
        super(currentNode, context, source);
        this.parent = parent;
        this.environment = environment;
    }

    @Override
    public RubyNode visitAliasNode(org.jruby.ast.AliasNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final org.jruby.ast.LiteralNode oldName = (org.jruby.ast.LiteralNode) node.getOldName();
        final org.jruby.ast.LiteralNode newName = (org.jruby.ast.LiteralNode) node.getNewName();

        return AliasNodeFactory.create(context, sourceSection, newName.getName(), oldName.getName(), new SelfNode(context, sourceSection));
    }

    @Override
    public RubyNode visitAndNode(org.jruby.ast.AndNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode x;

        if (node.getFirstNode() == null) {
            x = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            x = node.getFirstNode().accept(this);
        }

        RubyNode y;

        if (node.getSecondNode() == null) {
            y = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            y = node.getSecondNode().accept(this);
        }

        return new AndNode(context, sourceSection, x, y);
    }

    @Override
    public RubyNode visitArgsCatNode(org.jruby.ast.ArgsCatNode node) {
        final List<org.jruby.ast.Node> nodes = new ArrayList<>();
        collectArgsCatNodes(nodes, node);

        final List<RubyNode> translatedNodes = new ArrayList<>();

        for (org.jruby.ast.Node catNode : nodes) {
            translatedNodes.add(catNode.accept(this));
        }

        return new ArrayConcatNode(context, translate(node.getPosition()), translatedNodes.toArray(new RubyNode[translatedNodes.size()]));
    }

    // ArgsCatNodes can be nested - this collects them into a flat list of children
    private void collectArgsCatNodes(List<org.jruby.ast.Node> nodes, org.jruby.ast.ArgsCatNode node) {
        if (node.getFirstNode() instanceof org.jruby.ast.ArgsCatNode) {
            collectArgsCatNodes(nodes, (org.jruby.ast.ArgsCatNode) node.getFirstNode());
        } else {
            nodes.add(node.getFirstNode());
        }

        if (node.getSecondNode() instanceof org.jruby.ast.ArgsCatNode) {
            collectArgsCatNodes(nodes, (org.jruby.ast.ArgsCatNode) node.getSecondNode());
        } else {
            // ArgsCatNode implicitly splat its second argument. See Helpers.argsCat.
            Node secondNode = new SplatNode(node.getSecondNode().getPosition(), node.getSecondNode());
            nodes.add(secondNode);
        }
    }

    @Override
    public RubyNode visitArgsPushNode(org.jruby.ast.ArgsPushNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        return ArrayNodesFactory.PushOneNodeFactory.create(context, sourceSection, new RubyNode[]{
                KernelNodesFactory.DupNodeFactory.create(context, sourceSection, new RubyNode[]{
                        node.getFirstNode().accept(this)
                }),
                node.getSecondNode().accept(this)
        });
    }

    @Override
    public RubyNode visitArrayNode(org.jruby.ast.ArrayNode node) {
        final List<org.jruby.ast.Node> values = node.childNodes();

        final RubyNode[] translatedValues = new RubyNode[values.size()];

        for (int n = 0; n < values.size(); n++) {
            translatedValues[n] = values.get(n).accept(this);
        }

        return new ArrayLiteralNode.UninitialisedArrayLiteralNode(context, translate(node.getPosition()), translatedValues);
    }

    @Override
    public RubyNode visitAttrAssignNode(org.jruby.ast.AttrAssignNode node) {
        return visitAttrAssignNodeExtraArgument(node, null);
    }

    /**
     * See translateDummyAssignment to understand what this is for.
     */
    public RubyNode visitAttrAssignNodeExtraArgument(org.jruby.ast.AttrAssignNode node, RubyNode extraArgument) {
        final CallNode callNode = new CallNode(node.getPosition(), node.getReceiverNode(), node.getName(), node.getArgsNode(), null);
        boolean isAccessorOnSelf = (node.getReceiverNode() instanceof org.jruby.ast.SelfNode);
        return visitCallNodeExtraArgument(callNode, extraArgument, isAccessorOnSelf);
    }

    @Override
    public RubyNode visitBeginNode(org.jruby.ast.BeginNode node) {
        return node.getBodyNode().accept(this);
    }

    @Override
    public RubyNode visitBignumNode(org.jruby.ast.BignumNode node) {
        return new ObjectLiteralNode(context, translate(node.getPosition()), new RubyBignum(context.getCoreLibrary().getBignumClass(), node.getValue()));
    }

    @Override
    public RubyNode visitBlockNode(org.jruby.ast.BlockNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final List<RubyNode> translatedChildren = new ArrayList<>();

        for (org.jruby.ast.Node child : node.childNodes()) {
            if (child.getPosition() == InvalidSourcePosition.INSTANCE) {
                parentSourceSection = sourceSection;
            }

            final RubyNode translatedChild;

            try {
                translatedChild = child.accept(this);
            } finally {
                if (child.getPosition() == InvalidSourcePosition.INSTANCE) {
                    parentSourceSection = null;
                }
            }

            if (!(translatedChild instanceof DeadNode)) {
                translatedChildren.add(translatedChild);
            }
        }

        if (translatedChildren.size() == 1) {
            return translatedChildren.get(0);
        } else {
            return SequenceNode.sequence(context, sourceSection, translatedChildren.toArray(new RubyNode[translatedChildren.size()]));
        }
    }

    @Override
    public RubyNode visitBreakNode(org.jruby.ast.BreakNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode resultNode;

        if (node.getValueNode() == null) {
            parentSourceSection = sourceSection;

            try {
                resultNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
            } finally {
                parentSourceSection = null;
            }
        } else if (node.getValueNode().getPosition() == InvalidSourcePosition.INSTANCE) {
            parentSourceSection = sourceSection;

            try {
                resultNode = node.getValueNode().accept(this);
            } finally {
                parentSourceSection = null;
            }
        } else {
            resultNode = node.getValueNode().accept(this);
        }

        return new BreakNode(context, sourceSection, resultNode);
    }

    @Override
    public RubyNode visitCallNode(CallNode node) {
        return visitCallNodeExtraArgument(node, null, false);
    }

    /**
     * See translateDummyAssignment to understand what this is for.
     */
    public RubyNode visitCallNodeExtraArgument(CallNode node, RubyNode extraArgument, boolean ignoreVisibility) {
        final SourceSection sourceSection = translate(node.getPosition());

        final RubyNode receiverTranslated = node.getReceiverNode().accept(this);

        org.jruby.ast.Node args = node.getArgsNode();
        org.jruby.ast.Node block = node.getIterNode();

        if (block == null && args instanceof org.jruby.ast.IterNode) {
            final org.jruby.ast.Node temp = args;
            args = block;
            block = temp;
        }

        final ArgumentsAndBlockTranslation argumentsAndBlock = translateArgumentsAndBlock(sourceSection, block, args, extraArgument, node.getName());

        RubyNode translated;
        if (node.getName().equals("primitive") && receiverTranslated instanceof ReadConstantNode && ((ReadConstantNode) receiverTranslated).getName().equals("Rubinius")) {
            RubyNode callNode = new RubyCallNode(context, sourceSection, "send", receiverTranslated, argumentsAndBlock.getBlock(), argumentsAndBlock.isSplatted(), false, true, argumentsAndBlock.getArguments());
            translated = new TryNode(context, sourceSection, new ExceptionTranslatingNode(context, sourceSection, new ReturnNode(context, sourceSection, environment.getReturnID(), callNode)),
                    new RescueNode[] {new RescueAnyNode(context, sourceSection, new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject()))},
                    new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject()));
        } else {
            translated = new RubyCallNode(context, sourceSection, node.getName(), receiverTranslated, argumentsAndBlock.getBlock(), argumentsAndBlock.isSplatted(), ignoreVisibility, false, argumentsAndBlock.getArguments());
        }

        // return instrumenter.instrumentAsCall(translated, node.getName());
        return translated;
    }

    protected class ArgumentsAndBlockTranslation {

        private final RubyNode block;
        private final RubyNode[] arguments;
        private final boolean isSplatted;

        public ArgumentsAndBlockTranslation(RubyNode block, RubyNode[] arguments, boolean isSplatted) {
            super();
            this.block = block;
            this.arguments = arguments;
            this.isSplatted = isSplatted;
        }

        public RubyNode getBlock() {
            return block;
        }

        public RubyNode[] getArguments() {
            return arguments;
        }

        public boolean isSplatted() {
            return isSplatted;
        }

    }

    protected ArgumentsAndBlockTranslation translateArgumentsAndBlock(SourceSection sourceSection, org.jruby.ast.Node iterNode, org.jruby.ast.Node argsNode, RubyNode extraArgument, String nameToSetWhenTranslatingBlock) {
        assert !(argsNode instanceof org.jruby.ast.IterNode);

        final List<org.jruby.ast.Node> arguments = new ArrayList<>();
        org.jruby.ast.Node blockPassNode = null;

        boolean isSplatted = false;

        if (argsNode instanceof org.jruby.ast.ListNode) {
            arguments.addAll(argsNode.childNodes());
        } else if (argsNode instanceof org.jruby.ast.BlockPassNode) {
            final org.jruby.ast.BlockPassNode blockPass = (org.jruby.ast.BlockPassNode) argsNode;

            final org.jruby.ast.Node blockPassArgs = blockPass.getArgsNode();

            if (blockPassArgs instanceof org.jruby.ast.ListNode) {
                arguments.addAll(blockPassArgs.childNodes());
            } else if (blockPassArgs instanceof org.jruby.ast.ArgsCatNode) {
                arguments.add(blockPassArgs);
            } else if (blockPassArgs != null) {
                throw new UnsupportedOperationException("Don't know how to block pass " + blockPassArgs);
            }

            blockPassNode = blockPass.getBodyNode();
        } else if (argsNode instanceof org.jruby.ast.SplatNode) {
            isSplatted = true;
            arguments.add(argsNode);
        } else if (argsNode instanceof org.jruby.ast.ArgsCatNode) {
            isSplatted = true;
            arguments.add(argsNode);
        } else if (argsNode != null) {
            isSplatted = true;
            arguments.add(argsNode);
        }

        if (iterNode instanceof org.jruby.ast.BlockPassNode) {
            blockPassNode = ((org.jruby.ast.BlockPassNode) iterNode).getBodyNode();
        }

        currentCallMethodName = nameToSetWhenTranslatingBlock;

        RubyNode blockTranslated;

        if (blockPassNode != null) {
            blockTranslated = ProcCastNodeFactory.create(context, sourceSection, blockPassNode.accept(this));
        } else if (iterNode != null) {
            blockTranslated = iterNode.accept(this);

            if (blockTranslated instanceof ObjectLiteralNode && ((ObjectLiteralNode) blockTranslated).getObject() instanceof RubyNilClass) {
                blockTranslated = null;
            }
        } else {
            blockTranslated = null;
        }

        final List<RubyNode> argumentsTranslated = new ArrayList<>();

        for (org.jruby.ast.Node argument : arguments) {
            argumentsTranslated.add(argument.accept(this));
        }

        if (extraArgument != null) {
            argumentsTranslated.add(extraArgument);
        }

        final RubyNode[] argumentsTranslatedArray = argumentsTranslated.toArray(new RubyNode[argumentsTranslated.size()]);

        return new ArgumentsAndBlockTranslation(blockTranslated, argumentsTranslatedArray, isSplatted);
    }

    @Override
    public RubyNode visitCaseNode(org.jruby.ast.CaseNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode elseNode;

        if (node.getElseNode() != null) {
            elseNode = node.getElseNode().accept(this);
        } else {
            elseNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        }

        /*
         * There are two sorts of case - one compares a list of expressions against a value, the
         * other just checks a list of expressions for truth.
         */

        if (node.getCaseNode() != null) {
            // Evaluate the case expression and store it in a local

            final String tempName = environment.allocateLocalTemp("case");

            final RubyNode readTemp = environment.findLocalVarNode(tempName, sourceSection);

            final RubyNode assignTemp = ((ReadNode) readTemp).makeWriteNode(node.getCaseNode().accept(this));

            /*
             * Build an if expression from the whens and else. Work backwards because the first if
             * contains all the others in its else clause.
             */

            for (int n = node.getCases().size() - 1; n >= 0; n--) {
                final org.jruby.ast.WhenNode when = (org.jruby.ast.WhenNode) node.getCases().get(n);

                // Make a condition from the one or more expressions combined in an or expression

                final List<org.jruby.ast.Node> expressions;

                if (when.getExpressionNodes() instanceof org.jruby.ast.ListNode && !(when.getExpressionNodes() instanceof org.jruby.ast.ArrayNode)) {
                    expressions = when.getExpressionNodes().childNodes();
                } else {
                    expressions = Arrays.asList(when.getExpressionNodes());
                }

                final List<RubyNode> comparisons = new ArrayList<>();

                for (org.jruby.ast.Node expressionNode : expressions) {
                    final RubyNode rubyExpression = expressionNode.accept(this);

                    if (expressionNode instanceof org.jruby.ast.SplatNode) {
                        final SplatCastNode splatCastNode = (SplatCastNode) rubyExpression;
                        comparisons.add(new WhenSplatNode(context, sourceSection, NodeUtil.cloneNode(readTemp), splatCastNode));
                    } else if (expressionNode instanceof org.jruby.ast.ArgsCatNode) {
                        final ArrayConcatNode arrayConcatNode = (ArrayConcatNode) rubyExpression;
                        comparisons.add(new WhenSplatNode(context, sourceSection, NodeUtil.cloneNode(readTemp), arrayConcatNode));
                    } else {
                        comparisons.add(new RubyCallNode(context, sourceSection, "===", rubyExpression, null, false, NodeUtil.cloneNode(readTemp)));
                    }
                }

                RubyNode conditionNode = comparisons.get(comparisons.size() - 1);

                // As with the if nodes, we work backwards to make it left associative

                for (int i = comparisons.size() - 2; i >= 0; i--) {
                    conditionNode = new OrNode(context, sourceSection, comparisons.get(i), conditionNode);
                }

                // Create the if node

                final BooleanCastNode conditionCastNode = BooleanCastNodeFactory.create(context, sourceSection, conditionNode);

                RubyNode thenNode;

                if (when.getBodyNode() == null) {
                    thenNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
                } else {
                    thenNode = when.getBodyNode().accept(this);
                }

                final IfNode ifNode = new IfNode(context, sourceSection, conditionCastNode, thenNode, elseNode);

                // This if becomes the else for the next if

                elseNode = ifNode;
            }

            final RubyNode ifNode = elseNode;

            // A top-level block assigns the temp then runs the if

            return SequenceNode.sequence(context, sourceSection, assignTemp, ifNode);
        } else {
            for (int n = node.getCases().size() - 1; n >= 0; n--) {
                final org.jruby.ast.WhenNode when = (org.jruby.ast.WhenNode) node.getCases().get(n);

                // Make a condition from the one or more expressions combined in an or expression

                final List<org.jruby.ast.Node> expressions;

                if (when.getExpressionNodes() instanceof org.jruby.ast.ListNode) {
                    expressions = when.getExpressionNodes().childNodes();
                } else {
                    expressions = Arrays.asList(when.getExpressionNodes());
                }

                final List<RubyNode> tests = new ArrayList<>();

                for (org.jruby.ast.Node expressionNode : expressions) {
                    final RubyNode rubyExpression = expressionNode.accept(this);
                    tests.add(rubyExpression);
                }

                RubyNode conditionNode = tests.get(tests.size() - 1);

                // As with the if nodes, we work backwards to make it left associative

                for (int i = tests.size() - 2; i >= 0; i--) {
                    conditionNode = new OrNode(context, sourceSection, tests.get(i), conditionNode);
                }

                // Create the if node

                final BooleanCastNode conditionCastNode = BooleanCastNodeFactory.create(context, sourceSection, conditionNode);

                final RubyNode thenNode = when.getBodyNode().accept(this);

                final IfNode ifNode = new IfNode(context, sourceSection, conditionCastNode, thenNode, elseNode);

                // This if becomes the else for the next if

                elseNode = ifNode;
            }

            return elseNode;
        }
    }

    private RubyNode openModule(SourceSection sourceSection, RubyNode defineOrGetNode, String name, Node bodyNode) {
        LexicalScope newLexicalScope = environment.pushLexicalScope();
        try {
            final SharedMethodInfo sharedMethodInfo = new SharedMethodInfo(sourceSection, newLexicalScope, name, false, bodyNode, false);

            final TranslatorEnvironment newEnvironment = new TranslatorEnvironment(context, environment, environment.getParser(),
                    environment.getParser().allocateReturnID(), true, true, sharedMethodInfo, name, false);

            final ModuleTranslator classTranslator = new ModuleTranslator(currentNode, context, this, newEnvironment, source);

            final MethodDefinitionNode definitionMethod = classTranslator.compileClassNode(sourceSection, name, bodyNode);

            return new OpenModuleNode(context, sourceSection, defineOrGetNode, definitionMethod);
        } finally {
            environment.popLexicalScope();
        }
    }

    @Override
    public RubyNode visitClassNode(org.jruby.ast.ClassNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final String name = node.getCPath().getName();

        RubyNode lexicalParent = translateCPath(sourceSection, node.getCPath());

        RubyNode superClass;
        if (node.getSuperNode() != null) {
            superClass = node.getSuperNode().accept(this);
        } else {
            superClass = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getObjectClass());
        }

        final DefineOrGetClassNode defineOrGetClass = new DefineOrGetClassNode(context, sourceSection, name, lexicalParent, superClass);

        return openModule(sourceSection, defineOrGetClass, name, node.getBodyNode());
    }

    @Override
    public RubyNode visitClassVarAsgnNode(org.jruby.ast.ClassVarAsgnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final RubyNode receiver;
        if (useClassVariablesAsIfInClass) {
            receiver = new SelfNode(context, sourceSection);
        } else {
            receiver = ClassNodeFactory.create(context, sourceSection, new SelfNode(context, sourceSection));
        }
        final RubyNode rhs = node.getValueNode().accept(this);
        return new WriteClassVariableNode(context, sourceSection, node.getName(), receiver, rhs);
    }

    @Override
    public RubyNode visitClassVarNode(org.jruby.ast.ClassVarNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final RubyNode receiver;
        if (useClassVariablesAsIfInClass) {
            receiver = new SelfNode(context, sourceSection);
        } else {
            receiver = ClassNodeFactory.create(context, sourceSection, new SelfNode(context, sourceSection));
        }
        return new ReadClassVariableNode(context, sourceSection, node.getName(), receiver);
    }

    @Override
    public RubyNode visitColon2Node(org.jruby.ast.Colon2Node node) {
        // Qualified constant access, as in Mod::CONST
        if (!(node instanceof Colon2ConstNode)) {
            throw new UnsupportedOperationException(node.toString());
        }

        final RubyNode lhs = node.getLeftNode().accept(this);

        return new ReadConstantNode(context, translate(node.getPosition()), node.getName(), lhs, LexicalScope.NONE);
    }

    @Override
    public RubyNode visitColon3Node(org.jruby.ast.Colon3Node node) {
        // Root namespace constant access, as in ::Foo

        final SourceSection sourceSection = translate(node.getPosition());

        final ObjectLiteralNode root = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getObjectClass());

        return new ReadConstantNode(context, sourceSection, node.getName(), root, LexicalScope.NONE);
    }

    private RubyNode translateCPath(SourceSection sourceSection, org.jruby.ast.Colon3Node node) {
        if (node instanceof Colon2ImplicitNode) { // use current lexical scope
            return new LexicalScopeNode(context, sourceSection, environment.getLexicalScope());
        } else if (node instanceof Colon2ConstNode) { // A::B
            return node.childNodes().get(0).accept(this);
        } else { // Colon3Node: on top-level (Object)
            return new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getObjectClass());
        }
    }

    @Override
    public RubyNode visitComplexNode(ComplexNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        // TODO: implement Complex
        return node.getNumber().accept(this);
    }

    @Override
    public RubyNode visitConstDeclNode(org.jruby.ast.ConstDeclNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode moduleNode;
        Node constNode = node.getConstNode();
        if (constNode == null || constNode instanceof Colon2ImplicitNode) {
            moduleNode = new LexicalScopeNode(context, sourceSection, environment.getLexicalScope());
        } else if (constNode instanceof Colon2ConstNode) {
            constNode = ((Colon2Node) constNode).getLeftNode(); // Misleading doc, we only want the defined part.
            moduleNode = constNode.accept(this);
        } else if (constNode instanceof Colon3Node) {
            moduleNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getObjectClass());
        } else {
            throw new UnsupportedOperationException();
        }

        return new WriteConstantNode(context, sourceSection, node.getName(), moduleNode, node.getValueNode().accept(this));
    }

    @Override
    public RubyNode visitConstNode(org.jruby.ast.ConstNode node) {
        // Unqualified constant access, as in CONST
        final SourceSection sourceSection = translate(node.getPosition());

        final LexicalScope lexicalScope = environment.getLexicalScope();
        final RubyNode moduleNode = new LexicalScopeNode(context, sourceSection, lexicalScope);

        return new ReadConstantNode(context, sourceSection, node.getName(), moduleNode, lexicalScope);
    }

    @Override
    public RubyNode visitDAsgnNode(org.jruby.ast.DAsgnNode node) {
        return new org.jruby.ast.LocalAsgnNode(node.getPosition(), node.getName(), node.getDepth(), node.getValueNode()).accept(this);
    }

    @Override
    public RubyNode visitDRegxNode(org.jruby.ast.DRegexpNode node) {
        SourceSection sourceSection = translate(node.getPosition());

        final RubyNode stringNode = translateInterpolatedString(sourceSection, node.childNodes());

        return StringToRegexpNodeFactory.create(context, sourceSection, stringNode);
    }

    @Override
    public RubyNode visitDStrNode(org.jruby.ast.DStrNode node) {
        return translateInterpolatedString(translate(node.getPosition()), node.childNodes());
    }

    @Override
    public RubyNode visitDSymbolNode(org.jruby.ast.DSymbolNode node) {
        SourceSection sourceSection = translate(node.getPosition());

        final RubyNode stringNode = translateInterpolatedString(sourceSection, node.childNodes());

        return StringToSymbolNodeFactory.create(context, sourceSection, stringNode);
    }

    private RubyNode translateInterpolatedString(SourceSection sourceSection, List<org.jruby.ast.Node> childNodes) {
        final List<RubyNode> children = new ArrayList<>();

        for (org.jruby.ast.Node child : childNodes) {
            children.add(child.accept(this));
        }

        return new InterpolatedStringNode(context, sourceSection, children.toArray(new RubyNode[children.size()]));
    }

    @Override
    public RubyNode visitDVarNode(org.jruby.ast.DVarNode node) {
        RubyNode readNode = environment.findLocalVarNode(node.getName(), translate(node.getPosition()));

        if (readNode == null) {
            // If we haven't seen this dvar before it's possible that it's a block local variable

            final int depth = node.getDepth();

            TranslatorEnvironment e = environment;

            for (int n = 0; n < node.getDepth(); n++) {
                e = e.getParent();
            }

            e.declareVar(node.getName());
            readNode = e.findLocalVarNode(node.getName(), translate(node.getPosition()));
        }

        return readNode;
    }

    @Override
    public RubyNode visitDXStrNode(org.jruby.ast.DXStrNode node) {
        SourceSection sourceSection = translate(node.getPosition());

        final RubyNode string = translateInterpolatedString(sourceSection, node.childNodes());

        return new SystemNode(context, sourceSection, string);
    }

    @Override
    public RubyNode visitDefinedNode(org.jruby.ast.DefinedNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        org.jruby.ast.Node expressionNode = node.getExpressionNode();

        while (expressionNode instanceof org.jruby.ast.NewlineNode) {
            expressionNode = ((org.jruby.ast.NewlineNode) expressionNode).getNextNode();
        }

        return new DefinedNode(context, sourceSection, node.getExpressionNode().accept(this));
    }

    @Override
    public RubyNode visitDefnNode(org.jruby.ast.DefnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final SelfNode classNode = new SelfNode(context, sourceSection);
        return translateMethodDefinition(sourceSection, classNode, node.getName(), node, node.getArgsNode(), node.getBodyNode(), false);
    }

    @Override
    public RubyNode visitDefsNode(org.jruby.ast.DefsNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final RubyNode objectNode = node.getReceiverNode().accept(this);

        final SingletonClassNode singletonClassNode = SingletonClassNodeFactory.create(context, sourceSection, objectNode);

        return translateMethodDefinition(sourceSection, singletonClassNode, node.getName(), node, node.getArgsNode(), node.getBodyNode(), true);
    }

    protected RubyNode translateMethodDefinition(SourceSection sourceSection, RubyNode classNode, String methodName, org.jruby.ast.Node parseTree, org.jruby.ast.ArgsNode argsNode, org.jruby.ast.Node bodyNode, boolean ignoreLocalVisiblity) {
        final SharedMethodInfo sharedMethodInfo = new SharedMethodInfo(sourceSection, environment.getSharedMethodInfo().getLexicalScope(), methodName, false, parseTree, false);

        final TranslatorEnvironment newEnvironment = new TranslatorEnvironment(
                context, environment, environment.getParser(), environment.getParser().allocateReturnID(), true, true, sharedMethodInfo, methodName, false);

        // ownScopeForAssignments is the same for the defined method as the current one.

        final MethodTranslator methodCompiler = new MethodTranslator(currentNode, context, this, newEnvironment, false, parent == null, source);

        final MethodDefinitionNode functionExprNode = methodCompiler.compileFunctionNode(sourceSection, methodName, argsNode, bodyNode, ignoreLocalVisiblity);

        /*
         * In the top-level, methods are defined in the class of the main object. This is
         * counter-intuitive - I would have expected them to be defined in the singleton class.
         * Apparently this is a design decision to make top-level methods sort of global.
         * 
         * http://stackoverflow.com/questions/1761148/where-are-methods-defined-at-the-ruby-top-level
         */

        return new AddMethodNode(context, sourceSection, classNode, functionExprNode);
    }

    @Override
    public RubyNode visitDotNode(org.jruby.ast.DotNode node) {
        final RubyNode begin = node.getBeginNode().accept(this);
        final RubyNode end = node.getEndNode().accept(this);
        SourceSection sourceSection = translate(node.getPosition());

        // See RangeNode for why there is a node specifically for creating this one type
        return RangeLiteralNodeFactory.create(context, sourceSection, node.isExclusive(), begin, end);
    }

    @Override
    public RubyNode visitEncodingNode(org.jruby.ast.EncodingNode node) {
        SourceSection sourceSection = translate(node.getPosition());
        return new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getDefaultEncoding());
    }

    @Override
    public RubyNode visitEnsureNode(org.jruby.ast.EnsureNode node) {
        final RubyNode tryPart = node.getBodyNode().accept(this);
        final RubyNode ensurePart = node.getEnsureNode().accept(this);
        return new EnsureNode(context, translate(node.getPosition()), tryPart, ensurePart);
    }

    @Override
    public RubyNode visitEvStrNode(org.jruby.ast.EvStrNode node) {
        return node.getBody().accept(this);
    }

    @Override
    public RubyNode visitFCallNode(org.jruby.ast.FCallNode node) {
        if (Options.TRUFFLE_DEBUG_ENABLE_ASSERT_CONSTANT.load() && node.getName().equals("truffle_assert_constant")) {
            SourceSection sourceSection = translate(node.getPosition());
            return AssertCompilationConstantNodeFactory.create(context, sourceSection, node.getArgsNode().childNodes().get(0).accept(this));
        }

        final org.jruby.ast.Node receiver = new org.jruby.ast.SelfNode(node.getPosition());
        final CallNode callNode = new CallNode(node.getPosition(), receiver, node.getName(), node.getArgsNode(), node.getIterNode());

        return visitCallNodeExtraArgument(callNode, null, true);
    }

    @Override
    public RubyNode visitFalseNode(org.jruby.ast.FalseNode node) {
        return new BooleanLiteralNode(context, translate(node.getPosition()), false);
    }

    @Override
    public RubyNode visitFixnumNode(org.jruby.ast.FixnumNode node) {
        final long value = node.getValue();

        if (CoreLibrary.fitsIntoInteger(value)) {
            return new FixnumLiteralNode.IntegerFixnumLiteralNode(context, translate(node.getPosition()), (int) value);
        } else {
            return new FixnumLiteralNode.LongFixnumLiteralNode(context, translate(node.getPosition()), value);
        }
    }

    @Override
    public RubyNode visitFlipNode(org.jruby.ast.FlipNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final RubyNode begin = node.getBeginNode().accept(this);
        final RubyNode end = node.getEndNode().accept(this);

        final BooleanCastNode beginCast = BooleanCastNodeFactory.create(context, sourceSection, begin);
        final BooleanCastNode endCast = BooleanCastNodeFactory.create(context, sourceSection, end);
        final FlipFlopStateNode stateNode = createFlipFlopState(sourceSection, 0);

        return new FlipFlopNode(context, sourceSection, beginCast, endCast, stateNode, node.isExclusive());
    }

    protected FlipFlopStateNode createFlipFlopState(SourceSection sourceSection, int depth) {
        final FrameSlot frameSlot = environment.declareVar(environment.allocateLocalTemp("flipflop"));
        environment.getFlipFlopStates().add(frameSlot);

        if (depth == 0) {
            return new LocalFlipFlopStateNode(sourceSection, frameSlot);
        } else {
            return new LevelFlipFlopStateNode(sourceSection, depth, frameSlot);
        }
    }

    @Override
    public RubyNode visitFloatNode(org.jruby.ast.FloatNode node) {
        return new FloatLiteralNode(context, translate(node.getPosition()), node.getValue());
    }

    @Override
    public RubyNode visitForNode(org.jruby.ast.ForNode node) {
        /**
         * A Ruby for-loop, such as:
         * 
         * <pre>
         * for x in y
         *     z = x
         *     puts z
         * end
         * </pre>
         * 
         * naively desugars to:
         * 
         * <pre>
         * y.each do |x|
         *     z = x
         *     puts z
         * end
         * </pre>
         * 
         * The main difference is that z is always going to be local to the scope outside the block,
         * so it's a bit more like:
         * 
         * <pre>
         * z = nil unless z is already defined
         * y.each do |x|
         *    z = x
         *    puts x
         * end
         * </pre>
         * 
         * Which forces z to be defined in the correct scope. The parser already correctly calls z a
         * local, but then that causes us a problem as if we're going to translate to a block we
         * need a formal parameter - not a local variable. My solution to this is to add a
         * temporary:
         * 
         * <pre>
         * z = nil unless z is already defined
         * y.each do |temp|
         *    x = temp
         *    z = x
         *    puts x
         * end
         * </pre>
         * 
         * We also need that temp because the expression assigned in the for could be index
         * assignment, multiple assignment, or whatever:
         * 
         * <pre>
         * for x[0] in y
         *     z = x[0]
         *     puts z
         * end
         * </pre>
         * 
         * http://blog.grayproductions.net/articles/the_evils_of_the_for_loop
         * http://stackoverflow.com/questions/3294509/for-vs-each-in-ruby
         * 
         * The other complication is that normal locals should be defined in the enclosing scope,
         * unlike a normal block. We do that by setting a flag on this translator object when we
         * visit the new iter, translatingForStatement, which we recognise when visiting an iter
         * node.
         * 
         * Finally, note that JRuby's terminology is strange here. Normally 'iter' is a different
         * term for a block. Here, JRuby calls the object being iterated over the 'iter'.
         */

        final String temp = environment.allocateLocalTemp("for");

        final org.jruby.ast.Node receiver = node.getIterNode();

        /*
         * The x in for x in ... is like the nodes in multiple assignment - it has a dummy RHS which
         * we need to replace with our temp. Just like in multiple assignment this is really awkward
         * with the JRuby AST.
         */

        final org.jruby.ast.LocalVarNode readTemp = new org.jruby.ast.LocalVarNode(node.getPosition(), 0, temp);
        final org.jruby.ast.Node forVar = node.getVarNode();
        final org.jruby.ast.Node assignTemp = setRHS(forVar, readTemp);

        final org.jruby.ast.BlockNode bodyWithTempAssign = new org.jruby.ast.BlockNode(node.getPosition());
        bodyWithTempAssign.add(assignTemp);
        bodyWithTempAssign.add(node.getBodyNode());

        final org.jruby.ast.ArgumentNode blockVar = new org.jruby.ast.ArgumentNode(node.getPosition(), temp);
        final org.jruby.ast.ListNode blockArgsPre = new org.jruby.ast.ListNode(node.getPosition(), blockVar);
        final org.jruby.ast.ArgsNode blockArgs = new org.jruby.ast.ArgsNode(node.getPosition(), blockArgsPre, null, null, null, null, null, null);
        final org.jruby.ast.IterNode block = new org.jruby.ast.IterNode(node.getPosition(), blockArgs, node.getScope(), bodyWithTempAssign);

        final CallNode callNode = new CallNode(node.getPosition(), receiver, "each", null, block);

        translatingForStatement = true;
        final RubyCallNode translated = (RubyCallNode) callNode.accept(this);
        translatingForStatement = false;

        return new ForNode(context, translated.getSourceSection(), translated);
    }

    private static org.jruby.ast.Node setRHS(org.jruby.ast.Node node, org.jruby.ast.Node rhs) {
        if (node instanceof org.jruby.ast.LocalAsgnNode) {
            final org.jruby.ast.LocalAsgnNode localAsgnNode = (org.jruby.ast.LocalAsgnNode) node;
            return new org.jruby.ast.LocalAsgnNode(node.getPosition(), localAsgnNode.getName(), 0, rhs);
        } else if (node instanceof org.jruby.ast.DAsgnNode) {
            final org.jruby.ast.DAsgnNode dAsgnNode = (org.jruby.ast.DAsgnNode) node;
            return new org.jruby.ast.DAsgnNode(node.getPosition(), dAsgnNode.getName(), 0, rhs);
        } else if (node instanceof org.jruby.ast.MultipleAsgn19Node) {
            final org.jruby.ast.MultipleAsgn19Node multAsgnNode = (org.jruby.ast.MultipleAsgn19Node) node;
            final org.jruby.ast.MultipleAsgn19Node newNode = new org.jruby.ast.MultipleAsgn19Node(node.getPosition(), multAsgnNode.getPre(), multAsgnNode.getRest(), multAsgnNode.getPost());
            newNode.setValueNode(rhs);
            return newNode;
        } else if (node instanceof org.jruby.ast.InstAsgnNode) {
            final org.jruby.ast.InstAsgnNode instAsgnNode = (org.jruby.ast.InstAsgnNode) node;
            return new org.jruby.ast.InstAsgnNode(node.getPosition(), instAsgnNode.getName(), rhs);
        } else if (node instanceof org.jruby.ast.ClassVarAsgnNode) {
            final org.jruby.ast.ClassVarAsgnNode instAsgnNode = (org.jruby.ast.ClassVarAsgnNode) node;
            return new org.jruby.ast.ClassVarAsgnNode(node.getPosition(), instAsgnNode.getName(), rhs);
        } else if (node instanceof org.jruby.ast.ConstDeclNode) {
            final org.jruby.ast.ConstDeclNode constDeclNode = (org.jruby.ast.ConstDeclNode) node;
            return new org.jruby.ast.ConstDeclNode(node.getPosition(), constDeclNode.getName(), (org.jruby.ast.types.INameNode) constDeclNode.getConstNode(), rhs);
        } else {
            throw new UnsupportedOperationException("Don't know how to set the RHS of a " + node.getClass().getName());
        }
    }

    private final Set<String> readOnlyGlobalVariables = new HashSet<String>() {{
        add("$:");
        add("$LOAD_PATH");
        add("$-I");
        add("$\"");
        add("$LOADED_FEATURES");
        add("$<");
        add("$FILENAME");
        add("$?");
        add("$-a");
        add("$-l");
        add("$-p");
    }};

    @Override
    public RubyNode visitGlobalAsgnNode(org.jruby.ast.GlobalAsgnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final String name = node.getName();
        RubyNode rhs = node.getValueNode().accept(this);

        if (readOnlyGlobalVariables.contains(name)) {
            return new WriteReadOnlyGlobalNode(context, sourceSection, name, rhs);
        } else if (THREAD_LOCAL_GLOBAL_VARIABLES.contains(name)) {
            final ThreadLocalObjectNode threadLocalVariablesObjectNode = new ThreadLocalObjectNode(context, sourceSection);
            return new WriteInstanceVariableNode(context, sourceSection, name, threadLocalVariablesObjectNode, rhs, true);
        } else if (FRAME_LOCAL_GLOBAL_VARIABLES.contains(name)) {
            if (environment.getNeverAssignInParentScope()) {
                environment.declareVar(node.getName());
            }

            RubyNode localVarNode = environment.findLocalVarNode(node.getName(), sourceSection);

            if (localVarNode == null) {
                if (environment.hasOwnScopeForAssignments()) {
                    environment.declareVar(node.getName());
                }

                TranslatorEnvironment environmentToDeclareIn = environment;

                while (!environmentToDeclareIn.hasOwnScopeForAssignments()) {
                    environmentToDeclareIn = environmentToDeclareIn.getParent();
                }

                environmentToDeclareIn.declareVar(node.getName());
                localVarNode = environment.findLocalVarNode(node.getName(), sourceSection);

                if (localVarNode == null) {
                    throw new RuntimeException("shoudln't be here");
                }
            }

            return ((ReadNode) localVarNode).makeWriteNode(rhs);
        } else {
            if (name.equals("$~")) {
                rhs = new CheckMatchVariableTypeNode(context, sourceSection, rhs);
            }

            final ObjectLiteralNode globalVariablesObjectNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getGlobalVariablesObject());
            return new WriteInstanceVariableNode(context, sourceSection, name, globalVariablesObjectNode, rhs, true);
        }
    }

    @Override
    public RubyNode visitGlobalVarNode(org.jruby.ast.GlobalVarNode node) {
        final String name = node.getName();
        final SourceSection sourceSection = translate(node.getPosition());

        if (FRAME_LOCAL_GLOBAL_VARIABLES.contains(name)) {
            // Assignment is implicit for many of these, so we need to declare when we use

            environment.declareVarWhereAllowed(name);

            final RubyNode readNode = environment.findLocalVarNode(name, sourceSection);

            return readNode;
        } else if (THREAD_LOCAL_GLOBAL_VARIABLES.contains(name)) {
            final ThreadLocalObjectNode threadLocalVariablesObjectNode = new ThreadLocalObjectNode(context, sourceSection);
            return new ReadInstanceVariableNode(context, sourceSection, name, threadLocalVariablesObjectNode, true);
        } else {
            final ObjectLiteralNode globalVariablesObjectNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getGlobalVariablesObject());
            return new ReadInstanceVariableNode(context, sourceSection, name, globalVariablesObjectNode, true);
        }
    }

    @Override
    public RubyNode visitHashNode(org.jruby.ast.HashNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final List<RubyNode> keyValues = new ArrayList<>();

        for (KeyValuePair<Node, Node> pair: node.getPairs()) {
            if (pair.getKey() == null) {
                keyValues.add(new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject()));
            } else {
                keyValues.add(pair.getKey().accept(this));
            }

            if (pair.getValue() == null) {
                keyValues.add(new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject()));
            } else {
                keyValues.add(pair.getValue().accept(this));
            }
        }

        return HashLiteralNode.create(context, translate(node.getPosition()), keyValues.toArray(new RubyNode[keyValues.size()]));
    }

    @Override
    public RubyNode visitIfNode(org.jruby.ast.IfNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        org.jruby.ast.Node thenBody = node.getThenBody();

        if (thenBody == null) {
            thenBody = new org.jruby.ast.NilNode(node.getPosition());
        }

        org.jruby.ast.Node elseBody = node.getElseBody();

        if (elseBody == null) {
            elseBody = new org.jruby.ast.NilNode(node.getPosition());
        }

        RubyNode condition;

        if (node.getCondition() == null) {
            condition = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            condition = node.getCondition().accept(this);
        }

        final BooleanCastNode conditionCast = BooleanCastNodeFactory.create(context, sourceSection, condition);

        final RubyNode thenBodyTranslated = thenBody.accept(this);
        final RubyNode elseBodyTranslated = elseBody.accept(this);

        return new IfNode(context, sourceSection, conditionCast, thenBodyTranslated, elseBodyTranslated);
    }

    @Override
    public RubyNode visitInstAsgnNode(org.jruby.ast.InstAsgnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final String nameWithoutSigil = node.getName();

        final RubyNode receiver = new SelfNode(context, sourceSection);

        RubyNode rhs;

        if (node.getValueNode() == null) {
            rhs = new DeadNode(context, sourceSection);
        } else {
            rhs = node.getValueNode().accept(this);
        }

        return new WriteInstanceVariableNode(context, sourceSection, nameWithoutSigil, receiver, rhs, false);
    }

    @Override
    public RubyNode visitInstVarNode(org.jruby.ast.InstVarNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final String nameWithoutSigil = node.getName();

        final RubyNode receiver = new SelfNode(context, sourceSection);

        return new ReadInstanceVariableNode(context, sourceSection, nameWithoutSigil, receiver, false);
    }

    @Override
    public RubyNode visitIterNode(org.jruby.ast.IterNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        /*
         * In a block we do NOT allocate a new return ID - returns will return from the method, not
         * the block (in the general case, see Proc and the difference between Proc and Lambda for
         * specifics).
         */

        final boolean hasOwnScope = !translatingForStatement;

        // Unset this flag for any for any blocks within the for statement's body

        final SharedMethodInfo sharedMethodInfo = new SharedMethodInfo(sourceSection, environment.getSharedMethodInfo().getLexicalScope(), currentCallMethodName, true, node, false);

        final TranslatorEnvironment newEnvironment = new TranslatorEnvironment(
                context, environment, environment.getParser(), environment.getReturnID(), hasOwnScope, false, sharedMethodInfo, environment.getNamedMethodName(), true);
        final MethodTranslator methodCompiler = new MethodTranslator(currentNode, context, this, newEnvironment, true, parent == null, source);
        methodCompiler.translatingForStatement = translatingForStatement;

        org.jruby.ast.ArgsNode argsNode;

        if (node.getVarNode() instanceof org.jruby.ast.ArgsNode) {
            argsNode = (org.jruby.ast.ArgsNode) node.getVarNode();
        } else if (node.getVarNode() instanceof org.jruby.ast.DAsgnNode) {
            final org.jruby.ast.ArgumentNode arg = new org.jruby.ast.ArgumentNode(node.getPosition(), ((org.jruby.ast.DAsgnNode) node.getVarNode()).getName());
            final org.jruby.ast.ListNode preArgs = new org.jruby.ast.ArrayNode(node.getPosition(), arg);
            argsNode = new org.jruby.ast.ArgsNode(node.getPosition(), preArgs, null, null, null, null, null, null);
        } else if (node.getVarNode() == null) {
            argsNode = null;
        } else {
            throw new UnsupportedOperationException();
        }

        if (translatingForStatement && useClassVariablesAsIfInClass) {
            methodCompiler.useClassVariablesAsIfInClass = true;
        }

        return methodCompiler.compileFunctionNode(translate(node.getPosition()), sharedMethodInfo.getName(), argsNode, node.getBodyNode(), false);
    }

    @Override
    public RubyNode visitLocalAsgnNode(org.jruby.ast.LocalAsgnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        if (environment.getNeverAssignInParentScope()) {
            environment.declareVar(node.getName());
        }

        RubyNode lhs = environment.findLocalVarNode(node.getName(), sourceSection);

        if (lhs == null) {
            if (environment.hasOwnScopeForAssignments()) {
                environment.declareVar(node.getName());
            }

            TranslatorEnvironment environmentToDeclareIn = environment;

            while (!environmentToDeclareIn.hasOwnScopeForAssignments()) {
                environmentToDeclareIn = environmentToDeclareIn.getParent();
            }

            environmentToDeclareIn.declareVar(node.getName());
            lhs = environment.findLocalVarNode(node.getName(), sourceSection);

            if (lhs == null) {
                throw new RuntimeException("shoudln't be here");
            }
        }

        RubyNode rhs;

        if (node.getValueNode() == null) {
            rhs = new DeadNode(context, sourceSection);
        } else {
            if (node.getValueNode().getPosition() == InvalidSourcePosition.INSTANCE) {
                parentSourceSection = sourceSection;
            }

            try {
                rhs = node.getValueNode().accept(this);
            } finally {
                if (node.getValueNode().getPosition() == InvalidSourcePosition.INSTANCE) {
                    parentSourceSection = null;
                }
            }
        }

        RubyNode translated = ((ReadNode) lhs).makeWriteNode(rhs);

        final SharedMethodInfo methodIdentifier = environment.findMethodForLocalVar(node.getName());

        // return instrumenter.instrumentAsLocalAssignment(translated, methodIdentifier, node.getName());
        return translated;
    }

    @Override
    public RubyNode visitLocalVarNode(org.jruby.ast.LocalVarNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final String name = node.getName();

        RubyNode readNode = environment.findLocalVarNode(name, sourceSection);

        if (readNode == null) {
            /*

              This happens for code such as:

                def destructure4r((*c,d))
                    [c,d]
                end

               We're going to just assume that it should be there and add it...
             */

            environment.declareVar(node.getName());
            readNode = environment.findLocalVarNode(name, sourceSection);
        }

        return readNode;
    }

    @Override
    public RubyNode visitMatchNode(org.jruby.ast.MatchNode node) {
        final org.jruby.ast.Node argsNode = buildArrayNode(node.getPosition(), new org.jruby.ast.GlobalVarNode(node.getPosition(), "$_"));
        final org.jruby.ast.Node callNode = new CallNode(node.getPosition(), node.getRegexpNode(), "=~", argsNode, null);
        return callNode.accept(this);
    }

    @Override
    public RubyNode visitMatch2Node(org.jruby.ast.Match2Node node) {
        final org.jruby.ast.Node argsNode = buildArrayNode(node.getPosition(), node.getValueNode());
        final org.jruby.ast.Node callNode = new CallNode(node.getPosition(), node.getReceiverNode(), "=~", argsNode, null);
        return callNode.accept(this);
    }

    @Override
    public RubyNode visitMatch3Node(org.jruby.ast.Match3Node node) {
        final org.jruby.ast.Node argsNode = buildArrayNode(node.getPosition(), node.getValueNode());
        final org.jruby.ast.Node callNode = new CallNode(node.getPosition(), node.getReceiverNode(), "=~", argsNode, null);
        return callNode.accept(this);
    }

    @Override
    public RubyNode visitModuleNode(org.jruby.ast.ModuleNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final String name = node.getCPath().getName();

        RubyNode lexicalParent = translateCPath(sourceSection, node.getCPath());

        final DefineOrGetModuleNode defineModuleNode = new DefineOrGetModuleNode(context, sourceSection, name, lexicalParent);

        return openModule(sourceSection, defineModuleNode, name, node.getBodyNode());
    }

    @Override
    public RubyNode visitMultipleAsgnNode(org.jruby.ast.MultipleAsgnNode node) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RubyNode visitMultipleAsgnNode(org.jruby.ast.MultipleAsgn19Node node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final org.jruby.ast.ArrayNode preArray = (org.jruby.ast.ArrayNode) node.getPre();
        final org.jruby.ast.ArrayNode postArray = (org.jruby.ast.ArrayNode) node.getPost();
        final org.jruby.ast.Node rhs = node.getValueNode();

        RubyNode rhsTranslated;

        if (rhs == null) {
            context.getRuntime().getWarnings().warn(IRubyWarnings.ID.TRUFFLE, node.getPosition().getFile(), node.getPosition().getLine(), "no RHS for multiple assignment - using nil");
            rhsTranslated = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            rhsTranslated = rhs.accept(this);
        }

        final RubyNode result;

        if (preArray != null
                && node.getPost() == null
                && node.getRest() == null
                && rhsTranslated instanceof ArrayLiteralNode.UninitialisedArrayLiteralNode
                && ((ArrayLiteralNode.UninitialisedArrayLiteralNode) rhsTranslated).getValues().length == preArray.size()) {
            /*
             * We can deal with this common case be rewriting as
             *
             * temp1 = c; temp2 = d; a = temp1; b = temp2
             *
             * We can't just do
             *
             * a = c; b = d
             *
             * As we don't know if d depends on the original value of a.
             *
             * We also need to return an array [c, d], but we make that result elidable so it isn't
             * executed if it isn't actually demanded.
             */

            final RubyNode[] rhsValues = ((ArrayLiteralNode.UninitialisedArrayLiteralNode) rhsTranslated).getValues();
            final int assignedValuesCount = preArray.size();

            final RubyNode[] sequence = new RubyNode[assignedValuesCount * 2];

            final RubyNode[] tempValues = new RubyNode[assignedValuesCount];

            for (int n = 0; n < assignedValuesCount; n++) {
                final String tempName = environment.allocateLocalTemp("multi");
                final RubyNode readTemp = environment.findLocalVarNode(tempName, sourceSection);
                final RubyNode assignTemp = ((ReadNode) NodeUtil.cloneNode(readTemp)).makeWriteNode(rhsValues[n]);
                final RubyNode assignFinalValue = translateDummyAssignment(preArray.get(n), NodeUtil.cloneNode(readTemp));

                sequence[n] = assignTemp;
                sequence[assignedValuesCount + n] = assignFinalValue;

                tempValues[n] = NodeUtil.cloneNode(readTemp);
            }

            final RubyNode blockNode = SequenceNode.sequence(context, sourceSection, sequence);

            final ArrayLiteralNode.UninitialisedArrayLiteralNode arrayNode = new ArrayLiteralNode.UninitialisedArrayLiteralNode(context, sourceSection, tempValues);

            final ElidableResultNode elidableResult = new ElidableResultNode(context, sourceSection, blockNode, arrayNode);

            result = elidableResult;
        } else if (preArray != null) {
            /*
             * The other simple case is
             *
             * a, b, c = x
             *
             * If x is an array, then it's
             *
             * a[0] = x[0] etc
             *
             * If x isn't an array then it's
             *
             * a, b, c = [x, nil, nil]
             *
             * Which I believe is the same effect as
             *
             * a, b, c, = *x
             *
             * So we insert the splat cast node, even though it isn't there.
             */

            /*
             * Create a temp for the array.
             */

            final String tempName = environment.allocateLocalTemp("array");

            /*
             * Create a sequence of instructions, with the first being the literal array assigned to
             * the temp.
             */

            final List<RubyNode> sequence = new ArrayList<>();

            final RubyNode splatCastNode = SplatCastNodeFactory.create(context, sourceSection, translatingNextExpression ? SplatCastNode.NilBehavior.EMPTY_ARRAY : SplatCastNode.NilBehavior.ARRAY_WITH_NIL, rhsTranslated);

            final RubyNode writeTemp = ((ReadNode) environment.findLocalVarNode(tempName, sourceSection)).makeWriteNode(splatCastNode);

            sequence.add(writeTemp);

            /*
             * Then index the temp array for each assignment on the LHS.
             */

            for (int n = 0; n < preArray.size(); n++) {
                final ArrayIndexNode assignedValue = ArrayIndexNodeFactory.create(context, sourceSection, n, environment.findLocalVarNode(tempName, sourceSection));

                sequence.add(translateDummyAssignment(preArray.get(n), assignedValue));
            }

            if (node.getRest() != null) {
                final ArrayGetTailNode assignedValue = ArrayGetTailNodeFactory.create(context, sourceSection, preArray.size(), environment.findLocalVarNode(tempName, sourceSection));

                sequence.add(translateDummyAssignment(node.getRest(), assignedValue));
            }

            result = SequenceNode.sequence(context, sourceSection, sequence);
        } else if (node.getPre() == null
                && node.getPost() == null
                && node.getRest() instanceof org.jruby.ast.StarNode) {
            result = rhsTranslated;
        } else if (node.getPre() == null
                && node.getPost() == null
                && node.getRest() != null
                && rhs != null
                && !(rhs instanceof org.jruby.ast.ArrayNode)) {
            /*
             * *a = b
             *
             * >= 1.8, this seems to be the same as:
             *
             * a = *b
             */

            final RubyNode restTranslated = (node.getRest().accept(this)).getNonProxyNode();

            /*
             * Sometimes rest is a corrupt write with no RHS, like in other multiple assignments,
             * and sometimes it is already a read.
             */

            ReadNode restRead;

            if (restTranslated instanceof ReadNode) {
                restRead = (ReadNode) restTranslated;
            } else if (restTranslated instanceof WriteNode) {
                restRead = (ReadNode) ((WriteNode) restTranslated).makeReadNode();
            } else {
                throw new RuntimeException("Unknown form of multiple assignment " + node + " at " + node.getPosition());
            }

            final SplatCastNode rhsSplatCast = SplatCastNodeFactory.create(context, sourceSection, translatingNextExpression ? SplatCastNode.NilBehavior.EMPTY_ARRAY : SplatCastNode.NilBehavior.ARRAY_WITH_NIL, rhsTranslated);

            result = restRead.makeWriteNode(rhsSplatCast);
        } else if (node.getPre() == null
                && node.getPost() == null
                && node.getRest() != null
                && rhs != null
                && rhs instanceof org.jruby.ast.ArrayNode) {
            /*
             * *a = [b, c]
             *
             * This seems to be the same as:
             *
             * a = [b, c]
             */

            final RubyNode restTranslated = (node.getRest().accept(this)).getNonProxyNode();

            /*
             * Sometimes rest is a corrupt write with no RHS, like in other multiple assignments,
             * and sometimes it is already a read.
             */

            ReadNode restRead;

            if (restTranslated instanceof ReadNode) {
                restRead = (ReadNode) restTranslated;
            } else if (restTranslated instanceof WriteNode) {
                restRead = (ReadNode) ((WriteNode) restTranslated).makeReadNode();
            } else {
                throw new RuntimeException("Unknown form of multiple assignment " + node + " at " + node.getPosition());
            }

            result = restRead.makeWriteNode(rhsTranslated);
        } else if (node.getPre() == null && node.getRest() != null && node.getPost() != null) {
            /*
             * Something like
             *
             *     *a,b = [1, 2, 3, 4]
             */

            // This is very similar to the case with pre and rest, so unify with that

            /*
             * Create a temp for the array.
             */

            final String tempName = environment.allocateLocalTemp("array");

            /*
             * Create a sequence of instructions, with the first being the literal array assigned to
             * the temp.
             */

            final List<RubyNode> sequence = new ArrayList<>();

            final RubyNode splatCastNode = SplatCastNodeFactory.create(context, sourceSection, translatingNextExpression ? SplatCastNode.NilBehavior.EMPTY_ARRAY : SplatCastNode.NilBehavior.ARRAY_WITH_NIL, rhsTranslated);

            final RubyNode writeTemp = ((ReadNode) environment.findLocalVarNode(tempName, sourceSection)).makeWriteNode(splatCastNode);

            sequence.add(writeTemp);

            /*
             * Then index the temp array for each assignment on the LHS.
             */

            if (node.getRest() != null) {
                final ArrayDropTailNode assignedValue = ArrayDropTailNodeFactory.create(context, sourceSection, postArray.size(), environment.findLocalVarNode(tempName, sourceSection));

                sequence.add(translateDummyAssignment(node.getRest(), assignedValue));
            }

            for (int n = 0; n < postArray.size(); n++) {
                final ArrayIndexNode assignedValue = ArrayIndexNodeFactory.create(context, sourceSection, -(postArray.size() - n), environment.findLocalVarNode(tempName, sourceSection));

                sequence.add(translateDummyAssignment(postArray.get(n), assignedValue));
            }

            result = SequenceNode.sequence(context, sourceSection, sequence);
        } else {
            context.getRuntime().getWarnings().warn(IRubyWarnings.ID.TRUFFLE, node.getPosition().getFile(), node.getPosition().getLine(), node + " unknown form of multiple assignment");
            result = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        }

        return new AssignmentWrapperNode(context, sourceSection, result);
    }

    private RubyNode translateDummyAssignment(org.jruby.ast.Node dummyAssignment, RubyNode rhs) {
        final SourceSection sourceSection = translate(dummyAssignment.getPosition());

        /*
         * This is tricky. To represent the RHS of a multiple assignment they use corrupt assignment
         * values, in some cases with no value to be assigned, and in other cases with a dummy
         * value. We can't visit them normally, as they're corrupt. We can't just modify them to
         * have our RHS, as that's a node in our AST, not theirs. We can't use a dummy value in
         * their AST because I can't add new visitors to this interface.
         */

        RubyNode translated;

        if (dummyAssignment instanceof org.jruby.ast.LocalAsgnNode) {
            /*
             * They have a dummy NilImplicitNode as the RHS. Translate, convert to read, convert to
             * write which allows us to set the RHS.
             */

            final WriteNode dummyTranslated = (WriteNode) (dummyAssignment.accept(this)).getNonProxyNode();
            translated = ((ReadNode) dummyTranslated.makeReadNode()).makeWriteNode(rhs);
        } else if (dummyAssignment instanceof org.jruby.ast.InstAsgnNode) {
            /*
             * Same as before, just a different type of assignment.
             */

            final WriteInstanceVariableNode dummyTranslated = (WriteInstanceVariableNode) dummyAssignment.accept(this);
            translated = ((ReadNode) dummyTranslated.makeReadNode()).makeWriteNode(rhs);
        } else if (dummyAssignment instanceof org.jruby.ast.AttrAssignNode) {
            /*
             * They've given us an AttrAssignNode with the final argument, the assigned value,
             * missing. If we translate that we'll get foo.[]=(index), so missing the value. To
             * solve we have a special version of the visitCallNode that allows us to pass another
             * already translated argument, visitCallNodeExtraArgument. However, we initially have
             * an AttrAssignNode, so we also need a special version of that.
             */

            final org.jruby.ast.AttrAssignNode dummyAttrAssignment = (org.jruby.ast.AttrAssignNode) dummyAssignment;
            translated = visitAttrAssignNodeExtraArgument(dummyAttrAssignment, rhs);
        } else if (dummyAssignment instanceof org.jruby.ast.DAsgnNode) {
            final RubyNode dummyTranslated = dummyAssignment.accept(this);

            if (dummyTranslated.getNonProxyNode() instanceof WriteLevelVariableNode) {
                translated = ((ReadNode) ((WriteLevelVariableNode) dummyTranslated.getNonProxyNode()).makeReadNode()).makeWriteNode(rhs);
            } else {
                translated = ((ReadNode) ((WriteLocalVariableNode) dummyTranslated.getNonProxyNode()).makeReadNode()).makeWriteNode(rhs);
            }
        } else {
            translated = ((ReadNode) environment.findLocalVarNode(environment.allocateLocalTemp("dummy"), sourceSection)).makeWriteNode(rhs);
        }

        return translated;
    }

    @Override
    public RubyNode visitNewlineNode(org.jruby.ast.NewlineNode node) {
        return context.getASTProber().probeAsStatement(node.getNextNode().accept(this));
    }

    @Override
    public RubyNode visitNextNode(org.jruby.ast.NextNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode resultNode;

        if (node.getValueNode() == null) {
            parentSourceSection = sourceSection;

            try {
                resultNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
            } finally {
                parentSourceSection = null;
            }
        } else {
            final boolean t = translatingNextExpression;
            translatingNextExpression = true;

            if (node.getValueNode().getPosition() == InvalidSourcePosition.INSTANCE) {
                parentSourceSection = sourceSection;
            }

            try {
                resultNode = node.getValueNode().accept(this);
            } finally {
                if (node.getValueNode().getPosition() == InvalidSourcePosition.INSTANCE) {
                    parentSourceSection = null;
                }

                translatingNextExpression = t;
            }
        }

        return new NextNode(context, sourceSection, resultNode);
    }

    @Override
    public RubyNode visitNilNode(org.jruby.ast.NilNode node) {
        if (node.getPosition() == InvalidSourcePosition.INSTANCE && parentSourceSection == null) {
            return new DeadNode(context, null);
        }

        return new NilLiteralNode(context, translate(node.getPosition()));
    }

    @Override
    public RubyNode visitNthRefNode(org.jruby.ast.NthRefNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        // This is wrong I think - should reference one of the existing global variables or something like that

        final String name = "$" + node.getMatchNumber();

        return new GlobalVarNode(node.getPosition(), name).accept(this);
    }

    @Override
    public RubyNode visitOpAsgnAndNode(org.jruby.ast.OpAsgnAndNode node) {
        /*
         * This doesn't translate as you might expect!
         *
         * http://www.rubyinside.com/what-rubys-double-pipe-or-equals-really-does-5488.html
         */

        final SourceSection sourceSection = translate(node.getPosition());

        final org.jruby.ast.Node lhs = node.getFirstNode();
        final org.jruby.ast.Node rhs = node.getSecondNode();

        return new AssignmentWrapperNode(context, sourceSection, new AndNode(context, sourceSection, lhs.accept(this), rhs.accept(this)));
    }

    @Override
    public RubyNode visitOpAsgnNode(org.jruby.ast.OpAsgnNode node) {
        if (node.getOperatorName() == "||") {
            // Why does this ||= come through as a visitOpAsgnNode and not a visitOpAsgnOrNode?

            final String temp = environment.allocateLocalTemp("opassign");
            final org.jruby.ast.Node writeReceiverToTemp = new org.jruby.ast.LocalAsgnNode(node.getPosition(), temp, 0, node.getReceiverNode());
            final org.jruby.ast.Node readReceiverFromTemp = new org.jruby.ast.LocalVarNode(node.getPosition(), 0, temp);

            final org.jruby.ast.Node readMethod = new CallNode(node.getPosition(), readReceiverFromTemp, node.getVariableName(), null, null);
            final org.jruby.ast.Node writeMethod = new CallNode(node.getPosition(), readReceiverFromTemp, node.getVariableName() + "=", buildArrayNode(node.getPosition(),
                    node.getValueNode()), null);

            final SourceSection sourceSection = translate(node.getPosition());

            RubyNode lhs = readMethod.accept(this);
            RubyNode rhs = writeMethod.accept(this);

            return new AssignmentWrapperNode(context, sourceSection, SequenceNode.sequence(context, sourceSection, writeReceiverToTemp.accept(this), new OrNode(context, sourceSection, lhs, rhs)));
        }

        /*
         * We're going to de-sugar a.foo += c into a.foo = a.foo + c. Note that we can't evaluate a
         * more than once, so we put it into a temporary, and we're doing something more like:
         * 
         * temp = a; temp.foo = temp.foo + c
         */

        final String temp = environment.allocateLocalTemp("opassign");
        final org.jruby.ast.Node writeReceiverToTemp = new org.jruby.ast.LocalAsgnNode(node.getPosition(), temp, 0, node.getReceiverNode());
        final org.jruby.ast.Node readReceiverFromTemp = new org.jruby.ast.LocalVarNode(node.getPosition(), 0, temp);

        final org.jruby.ast.Node readMethod = new CallNode(node.getPosition(), readReceiverFromTemp, node.getVariableName(), null, null);
        final org.jruby.ast.Node operation = new CallNode(node.getPosition(), readMethod, node.getOperatorName(), buildArrayNode(node.getPosition(), node.getValueNode()), null);
        final org.jruby.ast.Node writeMethod = new CallNode(node.getPosition(), readReceiverFromTemp, node.getVariableName() + "=", buildArrayNode(node.getPosition(),
                        operation), null);

        final org.jruby.ast.BlockNode block = new org.jruby.ast.BlockNode(node.getPosition());
        block.add(writeReceiverToTemp);
        block.add(writeMethod);

        return block.accept(this);
    }

    @Override
    public RubyNode visitOpAsgnOrNode(org.jruby.ast.OpAsgnOrNode node) {
        /*
         * This doesn't translate as you might expect!
         *
         * http://www.rubyinside.com/what-rubys-double-pipe-or-equals-really-does-5488.html
         */

        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode lhs = node.getFirstNode().accept(this);
        RubyNode rhs = node.getSecondNode().accept(this);

        if (node.getFirstNode().needsDefinitionCheck()) {
            RubyNode defined = new DefinedNode(context, lhs.getSourceSection(), lhs);
            lhs = new AndNode(context, lhs.getSourceSection(), defined, lhs);
        }

        return new AssignmentWrapperNode(context, sourceSection, new OrNode(context, sourceSection, lhs, rhs));
    }

    @Override
    public RubyNode visitOpElementAsgnNode(org.jruby.ast.OpElementAsgnNode node) {
        /*
         * We're going to de-sugar a[b] += c into a[b] = a[b] + c. See discussion in
         * visitOpAsgnNode.
         */

        org.jruby.ast.Node index;

        if (node.getArgsNode() == null) {
            index = null;
        } else {
            index = node.getArgsNode().childNodes().get(0);
        }

        final org.jruby.ast.Node operand = node.getValueNode();

        final String temp = environment.allocateLocalTemp("opelementassign");
        final org.jruby.ast.Node writeArrayToTemp = new org.jruby.ast.LocalAsgnNode(node.getPosition(), temp, 0, node.getReceiverNode());
        final org.jruby.ast.Node readArrayFromTemp = new org.jruby.ast.LocalVarNode(node.getPosition(), 0, temp);

        final org.jruby.ast.Node arrayRead = new CallNode(node.getPosition(), readArrayFromTemp, "[]", buildArrayNode(node.getPosition(), index), null);

        final String op = node.getOperatorName();

        org.jruby.ast.Node operation = null;

        if (op.equals("||")) {
            operation = new org.jruby.ast.OrNode(node.getPosition(), arrayRead, operand);
        } else if (op.equals("&&")) {
            operation = new org.jruby.ast.AndNode(node.getPosition(), arrayRead, operand);
        } else {
            operation = new CallNode(node.getPosition(), arrayRead, node.getOperatorName(), buildArrayNode(node.getPosition(), operand), null);
        }

        final org.jruby.ast.Node arrayWrite = new CallNode(node.getPosition(), readArrayFromTemp, "[]=", buildArrayNode(node.getPosition(), index, operation), null);

        final org.jruby.ast.BlockNode block = new org.jruby.ast.BlockNode(node.getPosition());
        block.add(writeArrayToTemp);
        block.add(arrayWrite);

        return block.accept(this);
    }

    private static org.jruby.ast.ArrayNode buildArrayNode(org.jruby.lexer.yacc.ISourcePosition sourcePosition, org.jruby.ast.Node first, org.jruby.ast.Node... rest) {
        if (first == null) {
            return new org.jruby.ast.ArrayNode(sourcePosition);
        }

        final org.jruby.ast.ArrayNode array = new org.jruby.ast.ArrayNode(sourcePosition, first);

        for (org.jruby.ast.Node node : rest) {
            array.add(node);
        }

        return array;
    }

    @Override
    public RubyNode visitOrNode(org.jruby.ast.OrNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode x;

        if (node.getFirstNode() == null) {
            x = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            x = node.getFirstNode().accept(this);
        }

        RubyNode y;

        if (node.getSecondNode() == null) {
            y = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            y = node.getSecondNode().accept(this);
        }

        return new OrNode(context, sourceSection, x, y);
    }

    @Override
    public RubyNode visitPreExeNode(PreExeNode node) {
        return node.getBodyNode().accept(this);
    }

    @Override
    public RubyNode visitPostExeNode(PostExeNode node) {
        return node.getBodyNode().accept(this);
    }

    @Override
    public RubyNode visitRationalNode(RationalNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        // TODO: implement Rational
        return new FixnumLiteralNode.LongFixnumLiteralNode(context, sourceSection, node.getNumerator());
    }

    @Override
    public RubyNode visitRedoNode(org.jruby.ast.RedoNode node) {
        return new RedoNode(context, translate(node.getPosition()));
    }

    @Override
    public RubyNode visitRegexpNode(org.jruby.ast.RegexpNode node) {
        Regex regex = RubyRegexp.compile(currentNode, context, node.getValue().bytes(), node.getEncoding(), node.getOptions().toOptions());

        final RubyRegexp regexp = new RubyRegexp(context.getCoreLibrary().getRegexpClass(), regex, node.getValue());
        final ObjectLiteralNode literalNode = new ObjectLiteralNode(context, translate(node.getPosition()), regexp);
        return literalNode;
    }

    @Override
    public RubyNode visitRescueNode(org.jruby.ast.RescueNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode tryPart;

        if (node.getBodyNode() != null) {
            tryPart = node.getBodyNode().accept(this);
        } else {
            tryPart = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        }

        final List<RescueNode> rescueNodes = new ArrayList<>();

        org.jruby.ast.RescueBodyNode rescueBody = node.getRescueNode();

        while (rescueBody != null) {
            if (rescueBody.getExceptionNodes() != null) {
                if (rescueBody.getExceptionNodes() instanceof org.jruby.ast.ArrayNode) {
                    final List<org.jruby.ast.Node> exceptionNodes = ((org.jruby.ast.ArrayNode) rescueBody.getExceptionNodes()).childNodes();

                    final RubyNode[] handlingClasses = new RubyNode[exceptionNodes.size()];

                    for (int n = 0; n < handlingClasses.length; n++) {
                        handlingClasses[n] = exceptionNodes.get(n).accept(this);
                    }

                    RubyNode translatedBody;

                    if (rescueBody.getBodyNode() == null) {
                        translatedBody = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
                    } else {
                        translatedBody = rescueBody.getBodyNode().accept(this);
                    }

                    final RescueClassesNode rescueNode = new RescueClassesNode(context, sourceSection, handlingClasses, translatedBody);
                    rescueNodes.add(rescueNode);
                } else if (rescueBody.getExceptionNodes() instanceof org.jruby.ast.SplatNode) {
                    final org.jruby.ast.SplatNode splat = (org.jruby.ast.SplatNode) rescueBody.getExceptionNodes();

                    RubyNode splatTranslated;

                    if (splat.getValue() == null) {
                        splatTranslated = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
                    } else {
                        splatTranslated = splat.getValue().accept(this);
                    }

                    RubyNode bodyTranslated;

                    if (rescueBody.getBodyNode() == null) {
                        bodyTranslated = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
                    } else {
                        bodyTranslated = rescueBody.getBodyNode().accept(this);
                    }

                    final RescueSplatNode rescueNode = new RescueSplatNode(context, sourceSection, splatTranslated, bodyTranslated);
                    rescueNodes.add(rescueNode);
                } else {
                    unimplemented(node);
                }
            } else {
                RubyNode bodyNode;

                if (rescueBody.getBodyNode() == null) {
                    bodyNode = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
                } else {
                    bodyNode = rescueBody.getBodyNode().accept(this);
                }

                final RescueAnyNode rescueNode = new RescueAnyNode(context, sourceSection, bodyNode);
                rescueNodes.add(rescueNode);
            }

            rescueBody = rescueBody.getOptRescueNode();
        }

        RubyNode elsePart;

        if (node.getElseNode() != null) {
            elsePart = node.getElseNode().accept(this);
        } else {
            elsePart = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        }

        return new TryNode(context, sourceSection,
                new ExceptionTranslatingNode(context, sourceSection, tryPart),
                rescueNodes.toArray(new RescueNode[rescueNodes.size()]), elsePart);
    }

    @Override
    public RubyNode visitRetryNode(org.jruby.ast.RetryNode node) {
        return new RetryNode(context, translate(node.getPosition()));
    }

    @Override
    public RubyNode visitReturnNode(org.jruby.ast.ReturnNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode translatedChild;

        if (node.getValueNode() == null) {
            translatedChild = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            translatedChild = node.getValueNode().accept(this);
        }

        return new ReturnNode(context, sourceSection, environment.getReturnID(), translatedChild);
    }

    @Override
    public RubyNode visitSClassNode(org.jruby.ast.SClassNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        final RubyNode receiverNode = node.getReceiverNode().accept(this);

        final SingletonClassNode singletonClassNode = SingletonClassNodeFactory.create(context, sourceSection, receiverNode);

        return openModule(sourceSection, singletonClassNode, "(singleton-def)", node.getBodyNode());
    }

    @Override
    public RubyNode visitSValueNode(org.jruby.ast.SValueNode node) {
        return node.getValue().accept(this);
    }

    @Override
    public RubyNode visitSelfNode(org.jruby.ast.SelfNode node) {
        return new SelfNode(context, translate(node.getPosition()));
    }

    @Override
    public RubyNode visitSplatNode(org.jruby.ast.SplatNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode value;

        if (node.getValue() == null) {
            value = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            value = node.getValue().accept(this);
        }

        return SplatCastNodeFactory.create(context, sourceSection, SplatCastNode.NilBehavior.EMPTY_ARRAY, value);
    }

    @Override
    public RubyNode visitStrNode(org.jruby.ast.StrNode node) {
        return new StringLiteralNode(context, translate(node.getPosition()), node.getValue());
    }

    @Override
    public RubyNode visitSymbolNode(org.jruby.ast.SymbolNode node) {
        return new ObjectLiteralNode(context, translate(node.getPosition()), context.newSymbol(node.getName()));
    }

    @Override
    public RubyNode visitTrueNode(org.jruby.ast.TrueNode node) {
        return new BooleanLiteralNode(context, translate(node.getPosition()), true);
    }

    @Override
    public RubyNode visitUndefNode(org.jruby.ast.UndefNode node) {
        final SourceSection sourceSection = translate(node.getPosition());
        final SelfNode classNode = new SelfNode(context, sourceSection);
        return new UndefNode(context, sourceSection, classNode, ((org.jruby.ast.LiteralNode) node.getName()).getName());
    }

    @Override
    public RubyNode visitUntilNode(org.jruby.ast.UntilNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode condition;

        if (node.getConditionNode() == null) {
            condition = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            condition = node.getConditionNode().accept(this);
        }

        final BooleanCastNode conditionCast = BooleanCastNodeFactory.create(context, sourceSection, condition);
        final NotNode conditionCastNot = new NotNode(context, sourceSection, conditionCast);
        final BooleanCastNode conditionCastNotCast = BooleanCastNodeFactory.create(context, sourceSection, conditionCastNot);

        RubyNode body = node.getBodyNode().accept(this);

        body = context.getASTProber().probeAsPeriodic(body);

        if (node.evaluateAtStart()) {
            return new WhileNode(context, sourceSection, conditionCastNotCast, body);
        } else {
            return new DoWhileNode(context, sourceSection, conditionCastNotCast, body);
        }
    }

    @Override
    public RubyNode visitVCallNode(org.jruby.ast.VCallNode node) {
        final org.jruby.ast.Node receiver = new org.jruby.ast.SelfNode(node.getPosition());
        final org.jruby.ast.Node args = null;
        final CallNode callNode = new CallNode(node.getPosition(), receiver, node.getName(), args, null);

        return visitCallNodeExtraArgument(callNode, null, true);
    }

    @Override
    public RubyNode visitWhileNode(org.jruby.ast.WhileNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        RubyNode condition;

        if (node.getConditionNode() == null) {
            condition = new ObjectLiteralNode(context, sourceSection, context.getCoreLibrary().getNilObject());
        } else {
            condition = node.getConditionNode().accept(this);
        }

        final BooleanCastNode conditionCast = BooleanCastNodeFactory.create(context, sourceSection, condition);

        RubyNode body = node.getBodyNode().accept(this);

        body = context.getASTProber().probeAsPeriodic(body);

        if (node.evaluateAtStart()) {
            return new WhileNode(context, sourceSection, conditionCast, body);
        } else {
            return new DoWhileNode(context, sourceSection, conditionCast, body);
        }
    }

    @Override
    public RubyNode visitXStrNode(org.jruby.ast.XStrNode node) {
        SourceSection sourceSection = translate(node.getPosition());

        final StringLiteralNode literal = new StringLiteralNode(context, sourceSection, node.getValue());

        return new SystemNode(context, sourceSection, literal);
    }

    @Override
    public RubyNode visitYieldNode(org.jruby.ast.YieldNode node) {
        final List<org.jruby.ast.Node> arguments = new ArrayList<>();

        org.jruby.ast.Node argsNode = node.getArgsNode();

        final boolean unsplat = argsNode instanceof org.jruby.ast.SplatNode;

        if (unsplat) {
            argsNode = ((org.jruby.ast.SplatNode) argsNode).getValue();
        }

        if (argsNode != null) {
            if (argsNode instanceof org.jruby.ast.ListNode) {
                arguments.addAll((node.getArgsNode()).childNodes());
            } else {
                arguments.add(node.getArgsNode());
            }
        }

        final List<RubyNode> argumentsTranslated = new ArrayList<>();

        for (org.jruby.ast.Node argument : arguments) {
            argumentsTranslated.add(argument.accept(this));
        }

        final RubyNode[] argumentsTranslatedArray = argumentsTranslated.toArray(new RubyNode[argumentsTranslated.size()]);

        return new YieldNode(context, translate(node.getPosition()), argumentsTranslatedArray, unsplat);
    }

    @Override
    public RubyNode visitZArrayNode(org.jruby.ast.ZArrayNode node) {
        final RubyNode[] values = new RubyNode[0];

        return new ArrayLiteralNode.UninitialisedArrayLiteralNode(context, translate(node.getPosition()), values);
    }

    @Override
    public RubyNode visitBackRefNode(org.jruby.ast.BackRefNode node) {
       return new org.jruby.ast.GlobalVarNode(node.getPosition(), "$" + Character.toString(node.getType())).accept(this);
    }

    public RubyNode visitLambdaNode(org.jruby.ast.LambdaNode node) {
        final SourceSection sourceSection = translate(node.getPosition());

        // TODO(cs): code copied and modified from visitIterNode - extract common

        final SharedMethodInfo sharedMethodInfo = new SharedMethodInfo(sourceSection, environment.getSharedMethodInfo().getLexicalScope(), "(lambda)", true, node, false);

        final TranslatorEnvironment newEnvironment = new TranslatorEnvironment(
                context, environment, environment.getParser(), environment.getReturnID(), false, false, sharedMethodInfo, sharedMethodInfo.getName(), true);
        final MethodTranslator methodCompiler = new MethodTranslator(currentNode, context, this, newEnvironment, false, parent == null, source);

        org.jruby.ast.ArgsNode argsNode;

        if (node.getVarNode() instanceof org.jruby.ast.ArgsNode) {
            argsNode = (org.jruby.ast.ArgsNode) node.getVarNode();
        } else if (node.getVarNode() instanceof org.jruby.ast.DAsgnNode) {
            final org.jruby.ast.ArgumentNode arg = new org.jruby.ast.ArgumentNode(node.getPosition(), ((org.jruby.ast.DAsgnNode) node.getVarNode()).getName());
            final org.jruby.ast.ListNode preArgs = new org.jruby.ast.ArrayNode(node.getPosition(), arg);
            argsNode = new org.jruby.ast.ArgsNode(node.getPosition(), preArgs, null, null, null, null, null, null);
        } else if (node.getVarNode() == null) {
            argsNode = null;
        } else {
            throw new UnsupportedOperationException();
        }

        final MethodDefinitionNode definitionNode = methodCompiler.compileFunctionNode(translate(node.getPosition()), sharedMethodInfo.getName(), argsNode, node.getBodyNode(), false);

        return new LambdaNode(context, translate(node.getPosition()), definitionNode);
    }

    protected RubyNode initFlipFlopStates(SourceSection sourceSection) {
        final RubyNode[] initNodes = new RubyNode[environment.getFlipFlopStates().size()];

        for (int n = 0; n < initNodes.length; n++) {
            initNodes[n] = new InitFlipFlopSlotNode(context, sourceSection, environment.getFlipFlopStates().get(n));
        }

        return SequenceNode.sequence(context, sourceSection, initNodes);
    }

    @Override
    protected RubyNode defaultVisit(Node node) {
        return unimplemented(node);
    }

    protected RubyNode unimplemented(Node node) {
        context.getRuntime().getWarnings().warn(IRubyWarnings.ID.TRUFFLE, node.getPosition().getFile(), node.getPosition().getLine(), node + " does nothing - translating as nil");
        return new ObjectLiteralNode(context, translate(node.getPosition()), context.getCoreLibrary().getNilObject());
    }

    public TranslatorEnvironment getEnvironment() {
        return environment;
    }

    @Override
    protected String getIdentifier() {
        if (environment.isBlock()) {
            TranslatorEnvironment methodParent = environment.getParent();

            while (methodParent.isBlock()) {
                methodParent = methodParent.getParent();
            }

            return methodParent.getNamedMethodName();
        } else {
            return environment.getNamedMethodName();
        }
    }

}
