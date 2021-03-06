/*
 * Copyright 2020 Arthur Sadykov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.isarthur.netbeans.editor.generator.java;

import com.github.isarthur.netbeans.editor.generator.java.ui.GenerateGetterInvocationsDialog;
import com.github.isarthur.netbeans.editor.generator.java.ui.LocalMembersAndVarsPanel;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.java.source.ElementUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.TreeUtilities;
import org.netbeans.api.java.source.ui.ElementHeaders;
import org.netbeans.spi.editor.codegen.CodeGenerator;
import org.netbeans.spi.editor.codegen.CodeGeneratorContextProvider;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

public class GetterInvocationGenerator implements CodeGenerator {

    private static final String ERROR = "<error>"; //NOI18N
    private final JTextComponent component;
    private JavaSource javaSource;
    private TreeMaker treeMaker;
    private Scope scope;
    private ElementUtilities elementUtilities;
    private Types types;
    private final List<String> addedVariables = new ArrayList<>();
    private Document document;
    private TreeUtilities treeUtilities;
    private Element selectedElement;
    private int caretPosition;
    private int insertIndex;
    private Trees trees;
    private CompilationUnitTree compilationUnit;
    private TypeElement enclosingClass;
    private List<Element> locals;
    private TreePath currentPath;

    /**
     *
     * @param context containing JTextComponent and possibly other items registered by
     *                {@link CodeGeneratorContextProvider}
     */
    private GetterInvocationGenerator(Lookup context) { // Good practice is not to save Lookup outside ctor
        component = context.lookup(JTextComponent.class);
        initialize();
    }

    private GetterInvocationGenerator(JTextComponent component) {
        this.component = component;
        initialize();
    }

    public static GetterInvocationGenerator create(JTextComponent component) {
        return new GetterInvocationGenerator(component);
    }

    private void initialize() {
        caretPosition = component.getCaretPosition();
        document = component.getDocument();
        javaSource = JavaSource.forDocument(document);
        if (javaSource == null) {
            throw new IllegalStateException("The document is not associated with data type providing the JavaSource."); //NOI18N
        }
    }

    private List<Element> getLocalMembersAndVars() {
        List<Element> elements = new ArrayList<>();
        try {
            javaSource.runUserActionTask(compilationController -> {
                compilationController.toPhase(JavaSource.Phase.RESOLVED);
                elementUtilities = compilationController.getElementUtilities();
                treeUtilities = compilationController.getTreeUtilities();
                scope = treeUtilities.scopeFor(caretPosition);
                Iterable<? extends Element> localMembersAndVars =
                        elementUtilities.getLocalMembersAndVars(scope, (e, type) -> {
                            return e.getKind() == ElementKind.FIELD
                                    || e.getKind() == ElementKind.LOCAL_VARIABLE
                                    || e.getKind() == ElementKind.PARAMETER;
                        });
                localMembersAndVars.forEach(elements::add);
            }, true);
            Collections.sort(elements, (Element element1, Element element2) -> {
                return element1.getSimpleName().toString().compareTo(element2.getSimpleName().toString());
            });
            locals = elements;
            return Collections.unmodifiableList(elements);
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
        return Collections.emptyList();
    }

    /**
     * The name which will be inserted inside Insert Code dialog
     */
    @NbBundle.Messages("GETTER_INVOCATIONS=Getter Invocations...") //NOI18N
    @Override
    public String getDisplayName() {
        return NbBundle.getMessage(GetterInvocationGenerator.class, "GETTER_INVOCATIONS"); //NOI18N
    }

    public String getText() throws BadLocationException {
        return document.getText(0, document.getLength());
    }

    /**
     * This will be invoked when user chooses this Generator from Insert Code dialog
     */
    @Override
    public void invoke() {
        setSelectedElement();
        generateGetters();
    }

    private void setSelectedElement() {
        LocalMembersAndVarsPanel localMembersAndVarsPanel = LocalMembersAndVarsPanel.create();
        localMembersAndVarsPanel.addElements(getLocalMembersAndVars());
        GenerateGetterInvocationsDialog dialog = GenerateGetterInvocationsDialog.createAndShow(localMembersAndVarsPanel);
        if (dialog.isOkButtonPushed()) {
            selectedElement = localMembersAndVarsPanel.getSelectedElement();
        }
        dialog.saveBounds();
        dialog.dispose();
    }

    private void generateGetters() {
        try {
            javaSource.runModificationTask(workingCopy -> {
                workingCopy.toPhase(JavaSource.Phase.RESOLVED);
                compilationUnit = workingCopy.getCompilationUnit();
                treeMaker = workingCopy.getTreeMaker();
                elementUtilities = workingCopy.getElementUtilities();
                types = workingCopy.getTypes();
                treeUtilities = workingCopy.getTreeUtilities();
                trees = workingCopy.getTrees();
                currentPath = treeUtilities.pathFor(caretPosition);
                scope = treeUtilities.scopeFor(caretPosition);
                enclosingClass = scope.getEnclosingClass();
                TreePath blockPath = treeUtilities.getPathElementOfKind(Tree.Kind.BLOCK, currentPath);
                if (blockPath == null) {
                    return;
                }
                BlockTree oldTree = (BlockTree) blockPath.getLeaf();
                setInsertIndex(oldTree);
                BlockTree newTree = oldTree;
                if (selectedElement == null) {
                    return;
                }
                List<ExecutableElement> methods = getMethodsDeclaredInClassOf(selectedElement);
                for (ExecutableElement method : methods) {
                    if (isMethodNotPublic(method)
                            || isMethodReturnVoid(method)
                            || isMethodStatic(method)) {
                        continue;
                    }
                    String variableName = getVariableName(method);
                    if (variableName.isEmpty()) {
                        continue;
                    }
                    VariableTree variableTree = createGetterInvocationStatement(method, variableName);
                    newTree = insertGetterInvocationStatementIntoBlock(newTree, variableTree);
                }
                workingCopy.rewrite(oldTree, newTree);
            }).commit();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    private void setInsertIndex(BlockTree blockTree) {
        List<? extends StatementTree> statements = blockTree.getStatements();
        SourcePositions sourcePositions = trees.getSourcePositions();
        int size = statements.size();
        switch (size) {
            case 1: {
                StatementTree currentStatement = statements.get(0);
                long currentStartPosition = sourcePositions.getStartPosition(compilationUnit, currentStatement);
                if (caretPosition < currentStartPosition) {
                    insertIndex = 0;
                } else {
                    insertIndex = 1;
                }
                break;
            }
            case 2: {
                StatementTree previousStatement = statements.get(0);
                long previousStartPosition = sourcePositions.getStartPosition(compilationUnit, previousStatement);
                StatementTree currentStatement = statements.get(1);
                long currentStartPosition = sourcePositions.getStartPosition(compilationUnit, currentStatement);
                if (caretPosition < previousStartPosition) {
                    insertIndex = 0;
                } else if (currentStartPosition < caretPosition) {
                    insertIndex = size;
                } else {
                    insertIndex = 1;
                }
                break;
            }
            default:
                for (int i = 1; i < size; i++) {
                    StatementTree previousStatement = statements.get(i - 1);
                    long previousStartPosition = sourcePositions.getStartPosition(compilationUnit, previousStatement);
                    StatementTree currentStatement = statements.get(i);
                    long currentStartPosition = sourcePositions.getStartPosition(compilationUnit, currentStatement);
                    if (i < size - 1) {
                        if (previousStartPosition < caretPosition && caretPosition < currentStartPosition) {
                            insertIndex = i;
                            break;
                        }
                    } else {
                        if (currentStartPosition < caretPosition) {
                            insertIndex = size;
                        } else {
                            insertIndex = i;
                        }
                    }
                }
                break;
        }
    }

    private List<ExecutableElement> getMethodsDeclaredInClassOf(Element selectedElement) {
        TypeMirror typeMirror = selectedElement.asType();
        Element type = types.asElement(typeMirror);
        if (type == null) {
            return Collections.emptyList();
        }
        List<? extends Element> enclosedElements = type.getEnclosedElements();
        return ElementFilter.methodsIn(enclosedElements);
    }

    private boolean isMethodNotPublic(ExecutableElement method) {
        Set<Modifier> methodModifiers = method.getModifiers();
        return !methodModifiers.contains(Modifier.PUBLIC);
    }

    private boolean isMethodReturnVoid(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        return returnType.getKind() == TypeKind.VOID;
    }

    private boolean isMethodStatic(ExecutableElement method) {
        Set<Modifier> methodModifiers = method.getModifiers();
        return methodModifiers.contains(Modifier.STATIC);
    }

    private String getVariableName(ExecutableElement method) {
        String methodName = method.getSimpleName().toString();
        String variableName;
        if (methodName.startsWith("get") && methodName.length() > 3) { //NOI18N
            variableName = decapitalize(methodName.substring(3));
            if (variableName.equals("class")) { //NOI18N
                variableName = ""; //NOI18N
            }
        } else if (methodName.startsWith("is") && methodName.length() > 2) { //NOI18N
            variableName = decapitalize(methodName.substring(2));
        } else {
            variableName = ""; //NOI18N
        }
        return variableName;
    }

    private String decapitalize(String string) {
        if (string.isEmpty()) {
            return ""; //NOI18N
        }
        return string.substring(0, 1).toLowerCase().concat(string.substring(1));
    }

    private VariableTree createGetterInvocationStatement(ExecutableElement method, String variableName) {
        String varName = variableName;
        ModifiersTree modifiers = treeMaker.Modifiers(Collections.emptySet());
        Tree type = treeMaker.Type(method.getReturnType());
        IdentifierTree expressionTree = treeMaker.Identifier(selectedElement);
        List<ExpressionTree> methodArguments = evaluateMethodArguments(method);
        MethodInvocationTree methodInvocationTree = treeMaker.MethodInvocation(
                Collections.emptyList(),
                treeMaker.Identifier(method),
                methodArguments);
        MemberSelectTree initializer = treeMaker.MemberSelect(expressionTree, methodInvocationTree.toString());
        varName = incrementNumberOfVariableIfNeeded(varName);
        VariableTree variableTree = treeMaker.Variable(modifiers, varName, type, initializer);
        addedVariables.add(varName);
        return variableTree;
    }

    private List<ExpressionTree> evaluateMethodArguments(ExecutableElement method) {
        List<? extends VariableElement> parameters = method.getParameters();
        List<ExpressionTree> arguments = new ArrayList<>();
        for (VariableElement parameter : parameters) {
            TypeMirror elementType = parameter.asType();
            IdentifierTree identifierTree;
            VariableElement variableElement = instanceOf(elementType.toString(), null);
            if (variableElement != null) {
                identifierTree = treeMaker.Identifier(variableElement);
                arguments.add(identifierTree);
            } else {
                switch (elementType.getKind()) {
                    case BOOLEAN:
                        identifierTree = treeMaker.Identifier("false"); //NOI18N
                        break;
                    case BYTE:
                    case SHORT:
                    case INT:
                        identifierTree = treeMaker.Identifier("0"); //NOI18N
                        break;
                    case LONG:
                        identifierTree = treeMaker.Identifier("0L"); //NOI18N
                        break;
                    case FLOAT:
                        identifierTree = treeMaker.Identifier("0.0F"); //NOI18N
                        break;
                    case DOUBLE:
                        identifierTree = treeMaker.Identifier("0.0"); //NOI18N
                        break;
                    default:
                        identifierTree = treeMaker.Identifier("null"); //NOI18N
                }
                arguments.add(identifierTree);
            }
        }
        return arguments;
    }

    private VariableElement instanceOf(String typeName, String name) {
        try {
            TypeMirror type = type(typeName);
            VariableElement closest = null;
            int distance = Integer.MAX_VALUE;
            if (type != null) {
                for (Element element : locals) {
                    if (VariableElement.class.isInstance(element)
                            && !ERROR.contentEquals(element.getSimpleName())
                            && element.asType().getKind() != TypeKind.ERROR
                            && types.isAssignable(element.asType(), type)) {
                        if (name == null) {
                            return (VariableElement) element;
                        }
                        int d = ElementHeaders.getDistance(element.getSimpleName().toString()
                                .toLowerCase(), name.toLowerCase());
                        if (isSameType(element.asType(), type)) {
                            d -= 1000;
                        }
                        if (d < distance) {
                            distance = d;
                            closest = (VariableElement) element;
                        }
                    }
                }
            }
            return closest;
        } catch (Exception e) {
        }
        return null;
    }

    private TypeMirror type(String typeName) {
        try {
            String type = typeName.trim();
            if (!type.isEmpty()) {
                SourcePositions[] sourcePositions = new SourcePositions[1];
                StatementTree statement = treeUtilities.parseStatement("{" + type + " a;}", sourcePositions); //NOI18N
                if (statement.getKind() == Tree.Kind.BLOCK) {
                    List<? extends StatementTree> statements = ((BlockTree) statement).getStatements();
                    if (!statements.isEmpty()) {
                        StatementTree variable = statements.get(0);
                        if (variable.getKind() == Tree.Kind.VARIABLE) {
                            treeUtilities.attributeTree(statement, scope);
                            TypeMirror result = trees.getTypeMirror(new TreePath(currentPath,
                                    ((VariableTree) variable).getType()));
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return treeUtilities.parseType(type, enclosingClass);
            }
        } catch (Exception e) {
            Exceptions.printStackTrace(e);
        }
        return null;
    }

    private boolean isSameType(TypeMirror t1, TypeMirror t2) {
        if (types.isSameType(t1, t2)) {
            return true;
        }
        if (t1.getKind().isPrimitive() && types.isSameType(types.boxedClass((PrimitiveType) t1)
                .asType(), t2)) {
            return true;
        }
        return t2.getKind().isPrimitive() && types.isSameType(t1, types.boxedClass(
                (PrimitiveType) t1).asType());
    }

    private String incrementNumberOfVariableIfNeeded(String variableName) {
        String varName = variableName;
        if (addedVariables.contains(varName)) {
            List<String> occurences = new ArrayList<>();
            for (String variable : addedVariables) {
                if (variable.matches("^" + varName + "\\d*" + "$")) { //NOI18N
                    occurences.add(variable);
                }
            }
            if (!occurences.isEmpty()) {
                Collections.sort(occurences);
                String lastOccurence = occurences.get(occurences.size() - 1);
                String serialNumberString = lastOccurence.substring(varName.length());
                if (!serialNumberString.isEmpty()) {
                    try {
                        Integer serialNumber = Integer.parseInt(serialNumberString);
                        serialNumber++;
                        varName += serialNumber;
                    } catch (NumberFormatException ex) {
                        Exceptions.printStackTrace(ex);
                    }
                } else {
                    varName += 1;
                }
            }
        }
        return varName;
    }

    private BlockTree insertGetterInvocationStatementIntoBlock(BlockTree oldTree, VariableTree variableTree) {
        BlockTree newTree = treeMaker.insertBlockStatement(oldTree, insertIndex, variableTree);
        return newTree;
    }

    @MimeRegistration(mimeType = "text/x-java", service = CodeGenerator.Factory.class, position = 8000) //NOI18N
    public static class Factory implements CodeGenerator.Factory {

        @Override
        public List<? extends CodeGenerator> create(Lookup context) {
            JTextComponent editor = context.lookup(JTextComponent.class);
            JavaSource javaSource = JavaSource.forDocument(editor.getDocument());
            if (javaSource == null) {
                throw new IllegalStateException("The document is not associated with data type providing the JavaSource."); //NOI18N
            }
            AtomicBoolean insideBlock = new AtomicBoolean(true);
            try {
                javaSource.runUserActionTask(controller -> {
                    JavaSource.Phase phase = controller.toPhase(JavaSource.Phase.RESOLVED);
                    if (phase.compareTo(JavaSource.Phase.RESOLVED) < 0) {
                        insideBlock.set(false);
                        return;
                    }
                    TreeUtilities treeUtilities = controller.getTreeUtilities();
                    TreePath currentPath = treeUtilities.pathFor(editor.getCaretPosition());
                    TreePath blockPath = treeUtilities.getPathElementOfKind(Tree.Kind.BLOCK, currentPath);
                    if (blockPath == null) {
                        insideBlock.set(false);
                    }
                }, true);
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
                insideBlock.set(false);
            }
            return insideBlock.get()
                    ? Collections.singletonList(new GetterInvocationGenerator(context))
                    : Collections.emptyList();
        }
    }
}
