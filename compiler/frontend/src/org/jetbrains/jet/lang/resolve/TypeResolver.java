/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve;

import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.resolve.scopes.LazyScopeAdapter;
import org.jetbrains.jet.lang.types.*;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;
import org.jetbrains.jet.util.lazy.RecursionIntolerantLazyValue;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.jet.lang.diagnostics.Errors.*;
import static org.jetbrains.jet.lang.types.Variance.*;

public class TypeResolver {

    private AnnotationResolver annotationResolver;
    private DescriptorResolver descriptorResolver;
    private QualifiedExpressionResolver qualifiedExpressionResolver;
    private ModuleDescriptor moduleDescriptor;

    @Inject
    public void setDescriptorResolver(DescriptorResolver descriptorResolver) {
        this.descriptorResolver = descriptorResolver;
    }

    @Inject
    public void setAnnotationResolver(AnnotationResolver annotationResolver) {
        this.annotationResolver = annotationResolver;
    }

    @Inject
    public void setQualifiedExpressionResolver(QualifiedExpressionResolver qualifiedExpressionResolver) {
        this.qualifiedExpressionResolver = qualifiedExpressionResolver;
    }

    @Inject
    public void setModuleDescriptor(@NotNull ModuleDescriptor moduleDescriptor) {
        this.moduleDescriptor = moduleDescriptor;
    }

    @NotNull
    public JetType resolveType(@NotNull JetScope scope, @NotNull JetTypeReference typeReference, BindingTrace trace, boolean checkBounds) {
        return resolveType(new TypeResolutionContext(scope, trace, checkBounds, false), typeReference);
    }

    @NotNull
    public JetType resolveType(@NotNull TypeResolutionContext c, @NotNull JetTypeReference typeReference) {
        JetType cachedType = c.trace.getBindingContext().get(BindingContext.TYPE, typeReference);
        if (cachedType != null) return cachedType;

        List<AnnotationDescriptor> annotations = annotationResolver.getResolvedAnnotations(typeReference.getAnnotations(), c.trace);

        JetTypeElement typeElement = typeReference.getTypeElement();
        JetType type = resolveTypeElement(c, annotations, typeElement);
        c.trace.record(BindingContext.TYPE, typeReference, type);
        c.trace.record(BindingContext.TYPE_RESOLUTION_SCOPE, typeReference, c.scope);

        return type;
    }

    @NotNull
    private JetType resolveTypeElement(
            final TypeResolutionContext c,
            final List<AnnotationDescriptor> annotations,
            JetTypeElement typeElement
    ) {

        final JetType[] result = new JetType[1];
        if (typeElement != null) {
            typeElement.accept(new JetVisitorVoid() {
                @Override
                public void visitUserType(JetUserType type) {
                    JetSimpleNameExpression referenceExpression = type.getReferenceExpression();
                    String referencedName = type.getReferencedName();
                    if (referenceExpression == null || referencedName == null) {
                        return;
                    }

                    ClassifierDescriptor classifierDescriptor = resolveClass(c.scope, type, c.trace);
                    if (classifierDescriptor == null) {
                        resolveTypeProjections(c, ErrorUtils.createErrorType("No type").getConstructor(), type.getTypeArguments());
                        return;
                    }

                    c.trace.record(BindingContext.REFERENCE_TARGET, referenceExpression, classifierDescriptor);

                    if (classifierDescriptor instanceof TypeParameterDescriptor) {
                        TypeParameterDescriptor typeParameterDescriptor = (TypeParameterDescriptor) classifierDescriptor;

                        JetScope scopeForTypeParameter = getScopeForTypeParameter(c, typeParameterDescriptor);
                        if (scopeForTypeParameter instanceof ErrorUtils.ErrorScope) {
                            result[0] = ErrorUtils.createErrorType("?");
                        }
                        else {
                            result[0] = new JetTypeImpl(
                                    annotations,
                                    typeParameterDescriptor.getTypeConstructor(),
                                    TypeUtils.hasNullableLowerBound(typeParameterDescriptor),
                                    Collections.<TypeProjection>emptyList(),
                                    scopeForTypeParameter
                            );
                        }

                        resolveTypeProjections(c, ErrorUtils.createErrorType("No type").getConstructor(), type.getTypeArguments());

                        DeclarationDescriptor containing = typeParameterDescriptor.getContainingDeclaration();
                        if (containing instanceof ClassDescriptor) {
                            // Type parameter can't be inherited from member of parent class, so we can skip subclass check
                            DescriptorResolver.checkHasOuterClassInstance(c.scope, c.trace, referenceExpression, (ClassDescriptor) containing, false);
                        }
                    }
                    else if (classifierDescriptor instanceof ClassDescriptor) {
                        ClassDescriptor classDescriptor = (ClassDescriptor) classifierDescriptor;

                        TypeConstructor typeConstructor = classifierDescriptor.getTypeConstructor();
                        List<TypeProjection> arguments = resolveTypeProjections(c, typeConstructor, type.getTypeArguments());
                        List<TypeParameterDescriptor> parameters = typeConstructor.getParameters();
                        int expectedArgumentCount = parameters.size();
                        int actualArgumentCount = arguments.size();
                        if (ErrorUtils.isError(typeConstructor)) {
                            result[0] = ErrorUtils.createErrorType("[Error type: " + typeConstructor + "]");
                        }
                        else {
                            if (actualArgumentCount != expectedArgumentCount) {
                                if (actualArgumentCount == 0) {
                                    if (rhsOfIsExpression(type) || rhsOfIsPattern(type)) {
                                        c.trace.report(NO_TYPE_ARGUMENTS_ON_RHS_OF_IS_EXPRESSION.on(type, expectedArgumentCount, allStarProjectionsString(typeConstructor)));
                                    }
                                    else {
                                        c.trace.report(WRONG_NUMBER_OF_TYPE_ARGUMENTS.on(type, expectedArgumentCount));
                                    }
                                }
                                else {
                                    c.trace.report(WRONG_NUMBER_OF_TYPE_ARGUMENTS.on(type.getTypeArgumentList(), expectedArgumentCount));
                                }
                            }
                            else {
                                result[0] = new JetTypeImpl(
                                        annotations,
                                        typeConstructor,
                                        false,
                                        arguments,
                                        classDescriptor.getMemberScope(arguments)
                                );
                                if (c.checkBounds) {
                                    TypeSubstitutor substitutor = TypeSubstitutor.create(result[0]);
                                    for (int i = 0, parametersSize = parameters.size(); i < parametersSize; i++) {
                                        TypeParameterDescriptor parameter = parameters.get(i);
                                        JetType argument = arguments.get(i).getType();
                                        JetTypeReference typeReference = type.getTypeArguments().get(i).getTypeReference();

                                        if (typeReference != null) {
                                            DescriptorResolver.checkBounds(typeReference, argument, parameter, substitutor, c.trace);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                @Override
                public void visitNullableType(JetNullableType nullableType) {
                    JetType baseType = resolveTypeElement(c, annotations, nullableType.getInnerType());
                    if (baseType.isNullable()) {
                        c.trace.report(REDUNDANT_NULLABLE.on(nullableType));
                    }
                    else if (TypeUtils.hasNullableSuperType(baseType)) {
                        c.trace.report(BASE_WITH_NULLABLE_UPPER_BOUND.on(nullableType, baseType));
                    }
                    result[0] = TypeUtils.makeNullable(baseType);
                }

                @Override
                public void visitFunctionType(JetFunctionType type) {
                    JetTypeReference receiverTypeRef = type.getReceiverTypeRef();
                    JetType receiverType = receiverTypeRef == null ? null : resolveType(c, receiverTypeRef);

                    List<JetType> parameterTypes = new ArrayList<JetType>();
                    for (JetParameter parameter : type.getParameters()) {
                        parameterTypes.add(resolveType(c, parameter.getTypeReference()));
                    }

                    JetTypeReference returnTypeRef = type.getReturnTypeRef();
                    JetType returnType;
                    if (returnTypeRef != null) {
                        returnType = resolveType(c, returnTypeRef);
                    }
                    else {
                        returnType = KotlinBuiltIns.getInstance().getUnitType();
                    }
                    result[0] = KotlinBuiltIns.getInstance().getFunctionType(annotations, receiverType, parameterTypes, returnType);
                }

                @Override
                public void visitJetElement(JetElement element) {
                    c.trace.report(UNSUPPORTED.on(element, "Self-types are not supported yet"));
                }
            });
        }
        if (result[0] == null) {
            return ErrorUtils.createErrorType(typeElement == null ? "No type element" : typeElement.getText());
        }
        return result[0];
    }

    private static boolean rhsOfIsExpression(@NotNull JetUserType type) {
        // Look for the FIRST expression containing this type
        JetExpression outerExpression = PsiTreeUtil.getParentOfType(type, JetExpression.class);
        if (outerExpression instanceof JetIsExpression) {
            JetIsExpression isExpression = (JetIsExpression) outerExpression;
            // If this expression is JetIsExpression, and the type is the outermost on the RHS
            if (type.getParent() == isExpression.getTypeRef()) {
                return true;
            }
        }
        return false;
    }

    private static boolean rhsOfIsPattern(@NotNull JetUserType type) {
        // Look for the is-pattern containing this type
        JetWhenConditionIsPattern outerPattern = PsiTreeUtil.getParentOfType(type, JetWhenConditionIsPattern.class, false, JetExpression.class);
        if (outerPattern == null) return false;
        // We are interested only in the outermost type on the RHS
        return type.getParent() == outerPattern.getTypeRef();
    }

    private JetScope getScopeForTypeParameter(TypeResolutionContext c, final TypeParameterDescriptor typeParameterDescriptor) {
        if (c.checkBounds) {
            return typeParameterDescriptor.getUpperBoundsAsType().getMemberScope();
        }
        else {
            return new LazyScopeAdapter(new RecursionIntolerantLazyValue<JetScope>() {
                @Override
                protected JetScope compute() {
                    return typeParameterDescriptor.getUpperBoundsAsType().getMemberScope();
                }
            });
        }
    }

    private List<JetType> resolveTypes(JetScope scope, List<JetTypeReference> argumentElements, BindingTrace trace, boolean checkBounds) {
        List<JetType> arguments = new ArrayList<JetType>();
        for (JetTypeReference argumentElement : argumentElements) {
            arguments.add(resolveType(scope, argumentElement, trace, checkBounds));
        }
        return arguments;
    }

    @NotNull
    private List<TypeProjection> resolveTypeProjections(
            TypeResolutionContext c,
            TypeConstructor constructor,
            List<JetTypeProjection> argumentElements
    ) {
        List<TypeProjection> arguments = new ArrayList<TypeProjection>();
        for (int i = 0, argumentElementsSize = argumentElements.size(); i < argumentElementsSize; i++) {
            JetTypeProjection argumentElement = argumentElements.get(i);

            JetProjectionKind projectionKind = argumentElement.getProjectionKind();
            JetType type;
            if (projectionKind == JetProjectionKind.STAR) {
                List<TypeParameterDescriptor> parameters = constructor.getParameters();
                if (parameters.size() > i) {
                    TypeParameterDescriptor parameterDescriptor = parameters.get(i);
                    arguments.add(SubstitutionUtils.makeStarProjection(parameterDescriptor));
                }
                else {
                    arguments.add(new TypeProjection(OUT_VARIANCE, ErrorUtils.createErrorType("*")));
                }
            }
            else {
                // TODO : handle the Foo<in *> case
                type = resolveType(c, argumentElement.getTypeReference());
                Variance kind = resolveProjectionKind(projectionKind);
                if (constructor.getParameters().size() > i) {
                    TypeParameterDescriptor parameterDescriptor = constructor.getParameters().get(i);
                    if (kind != INVARIANT && parameterDescriptor.getVariance() != INVARIANT) {
                        if (kind == parameterDescriptor.getVariance()) {
                            c.trace.report(REDUNDANT_PROJECTION.on(argumentElement, constructor.getDeclarationDescriptor()));
                        }
                        else {
                            c.trace.report(CONFLICTING_PROJECTION.on(argumentElement, constructor.getDeclarationDescriptor()));
                        }
                    }
                }
                arguments.add(new TypeProjection(kind, type));
            }
        }
        return arguments;
    }

    @NotNull
    public static Variance resolveProjectionKind(@NotNull JetProjectionKind projectionKind) {
        Variance kind = null;
        switch (projectionKind) {
            case IN:
                kind = IN_VARIANCE;
                break;
            case OUT:
                kind = OUT_VARIANCE;
                break;
            case NONE:
                kind = INVARIANT;
                break;
            default:
                // NOTE: Star projections must be handled before this method is called
                throw new IllegalStateException("Illegal projection kind:" + projectionKind);
        }
        return kind;
    }

    @Nullable
    public ClassifierDescriptor resolveClass(JetScope scope, JetUserType userType, BindingTrace trace) {
        Collection<? extends DeclarationDescriptor> descriptors = qualifiedExpressionResolver.lookupDescriptorsForUserType(userType, scope, trace);
        for (DeclarationDescriptor descriptor : descriptors) {
            if (descriptor instanceof ClassifierDescriptor) {
                ImportsResolver.reportPlatformClassMappedToKotlin(moduleDescriptor, trace, userType, descriptor);
                return (ClassifierDescriptor) descriptor;
            }
        }
        return null;
    }

    @NotNull
    private static String allStarProjectionsString(@NotNull TypeConstructor constructor) {
        int size = constructor.getParameters().size();
        assert size != 0 : "No projections possible for a nilary type constructor" + constructor;
        ClassifierDescriptor declarationDescriptor = constructor.getDeclarationDescriptor();
        assert declarationDescriptor != null : "No declaration descriptor for type constructor " + constructor;
        String name = declarationDescriptor.getName().asString();

        return TypeUtils.getTypeNameAndStarProjectionsString(name, size);
    }
}
