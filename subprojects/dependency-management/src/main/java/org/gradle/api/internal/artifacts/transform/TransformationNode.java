/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.execution.plan.Node;
import org.gradle.execution.plan.SelfExecutingNode;
import org.gradle.execution.plan.TaskDependencyResolver;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.Try;
import org.gradle.internal.model.CalculatedValue;
import org.gradle.internal.model.CalculatedValueContainer;
import org.gradle.internal.operations.BuildOperationCategory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.scan.UsedByScanPlugin;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class TransformationNode extends Node implements SelfExecutingNode {
    private static final AtomicInteger ORDER_COUNTER = new AtomicInteger();

    private final int order = ORDER_COUNTER.incrementAndGet();
    protected final TransformationStep transformationStep;
    protected final ExecutionGraphDependenciesResolver dependenciesResolver;
    protected final BuildOperationExecutor buildOperationExecutor;
    protected final ArtifactTransformListener transformListener;

    public static ChainedTransformationNode chained(TransformationStep current, TransformationNode previous, ExecutionGraphDependenciesResolver executionGraphDependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
        return new ChainedTransformationNode(current, previous, executionGraphDependenciesResolver, buildOperationExecutor, transformListener);
    }

    public static InitialTransformationNode initial(TransformationStep initial, ResolvedArtifactSet.LocalArtifactSet localArtifacts, ExecutionGraphDependenciesResolver executionGraphDependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
        return new InitialTransformationNode(initial, localArtifacts, executionGraphDependenciesResolver, buildOperationExecutor, transformListener);
    }

    protected TransformationNode(TransformationStep transformationStep, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
        this.transformationStep = transformationStep;
        this.dependenciesResolver = dependenciesResolver;
        this.buildOperationExecutor = buildOperationExecutor;
        this.transformListener = transformListener;
    }

    public abstract ResolvedArtifactSet.LocalArtifactSet getInputArtifacts();

    @Nullable
    @Override
    public ProjectInternal getOwningProject() {
        return transformationStep.getOwningProject();
    }

    @Override
    public boolean isPublicNode() {
        return true;
    }

    @Override
    public boolean requiresMonitoring() {
        return false;
    }

    @Override
    public void resolveMutations() {
        // Assume for now that no other node is going to destroy the transform outputs, or overlap with them
    }

    @Override
    public String toString() {
        return transformationStep.getDisplayName();
    }

    public TransformationStep getTransformationStep() {
        return transformationStep;
    }

    public ExecutionGraphDependenciesResolver getDependenciesResolver() {
        return dependenciesResolver;
    }

    public Try<TransformationSubject> getTransformedSubject() {
        return getResult().getValue();
    }

    @Override
    public void execute(NodeExecutionContext context) {
        getResult().calculateNow(context);
    }

    public void executeIfNotAlready() {
        transformationStep.isolateParameters();
        getResult().calculateNow(null);
    }

    protected abstract CalculatedValueContainer<TransformationSubject> getResult();

    @Override
    public Set<Node> getFinalizers() {
        return Collections.emptySet();
    }

    @Override
    public void prepareForExecution() {
    }

    @Nullable
    @Override
    public ResourceLock getProjectToLock() {
        // Transforms do not require project state
        return null;
    }

    @Override
    public List<ResourceLock> getResourcesToLock() {
        return Collections.emptyList();
    }

    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void rethrowNodeFailure() {
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        TransformationNode otherTransformation = (TransformationNode) other;
        return order - otherTransformation.order;
    }

    protected void processDependencies(Action<Node> processHardSuccessor, Set<Node> dependencies) {
        for (Node dependency : dependencies) {
            addDependencySuccessor(dependency);
            processHardSuccessor.execute(dependency);
        }
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, transformationStep));
        processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, dependenciesResolver.computeDependencyNodes(transformationStep)));
    }

    public static class InitialTransformationNode extends TransformationNode {
        private final ResolvedArtifactSet.LocalArtifactSet artifacts;
        private final CalculatedValueContainer<TransformationSubject> result = new CalculatedValueContainer<>(new CalculatedValue<TransformationSubject>() {
            @Override
            public DisplayName getDisplayName() {
                return Describables.of(InitialTransformationNode.this);
            }

            @Override
            public TransformationSubject calculateValue(NodeExecutionContext context) {
                return buildOperationExecutor.call(new ArtifactTransformationStepBuildOperation() {
                    @Override
                    protected TransformationSubject transform() {
                        TransformationSubject initialArtifactTransformationSubject;
                        try {
                            initialArtifactTransformationSubject = artifacts.calculateSubject();
                        } catch (ResolveException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            throw new DefaultLenientConfiguration.ArtifactResolveException("artifacts", transformationStep.getDisplayName(), "artifact transform", Collections.singleton(e));
                        }

                        return transformationStep.createInvocation(initialArtifactTransformationSubject, dependenciesResolver, context).invoke().get();
                    }

                    @Override
                    protected String describeSubject() {
                        return artifacts.getDisplayName();
                    }
                });
            }
        });

        public InitialTransformationNode(TransformationStep transformationStep, ResolvedArtifactSet.LocalArtifactSet artifacts, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
            super(transformationStep, dependenciesResolver, buildOperationExecutor, transformListener);
            this.artifacts = artifacts;
        }

        @Override
        public ResolvedArtifactSet.LocalArtifactSet getInputArtifacts() {
            return artifacts;
        }

        @Override
        protected CalculatedValueContainer<TransformationSubject> getResult() {
            return result;
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            super.resolveDependencies(dependencyResolver, processHardSuccessor);
            processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, artifacts.getTaskDependencies()));
        }
    }

    public static class ChainedTransformationNode extends TransformationNode {
        private final TransformationNode previousTransformationNode;
        private final CalculatedValueContainer<TransformationSubject> result = new CalculatedValueContainer<>(new CalculatedValue<TransformationSubject>() {
            @Override
            public DisplayName getDisplayName() {
                return Describables.of(ChainedTransformationNode.this);
            }

            @Override
            public TransformationSubject calculateValue(NodeExecutionContext context) {
                return buildOperationExecutor.call(new ArtifactTransformationStepBuildOperation() {
                    @Override
                    protected TransformationSubject transform() {
                        return previousTransformationNode.getTransformedSubject().flatMap(transformedSubject ->
                            transformationStep.createInvocation(transformedSubject, dependenciesResolver, context).invoke()).get();
                    }

                    @Override
                    protected String describeSubject() {
                        return previousTransformationNode.getTransformedSubject()
                            .map(Describable::getDisplayName)
                            .getOrMapFailure(Throwable::getMessage);
                    }
                });
            }
        });

        public ChainedTransformationNode(TransformationStep transformationStep, TransformationNode previousTransformationNode, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
            super(transformationStep, dependenciesResolver, buildOperationExecutor, transformListener);
            this.previousTransformationNode = previousTransformationNode;
        }

        @Override
        public ResolvedArtifactSet.LocalArtifactSet getInputArtifacts() {
            return previousTransformationNode.getInputArtifacts();
        }

        public TransformationNode getPreviousTransformationNode() {
            return previousTransformationNode;
        }

        @Override
        protected CalculatedValueContainer<TransformationSubject> getResult() {
            return result;
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            super.resolveDependencies(dependencyResolver, processHardSuccessor);
            addDependencySuccessor(previousTransformationNode);
            processHardSuccessor.execute(previousTransformationNode);
        }

    }

    private abstract class ArtifactTransformationStepBuildOperation implements CallableBuildOperation<TransformationSubject> {

        @UsedByScanPlugin("The string is used for filtering out artifact transform logs in Gradle Enterprise")
        private static final String TRANSFORMING_PROGRESS_PREFIX = "Transforming ";

        @Override
        public final BuildOperationDescriptor.Builder description() {
            String transformerName = transformationStep.getDisplayName();
            String subjectName = describeSubject();
            String basicName = subjectName + " with " + transformerName;
            return BuildOperationDescriptor.displayName("Transform " + basicName)
                .progressDisplayName(TRANSFORMING_PROGRESS_PREFIX + basicName)
                .metadata(BuildOperationCategory.TRANSFORM)
                .details(new ExecuteScheduledTransformationStepBuildOperationDetails(TransformationNode.this, transformerName, subjectName));
        }

        protected abstract String describeSubject();

        @Override
        public TransformationSubject call(BuildOperationContext context) {
            context.setResult(ExecuteScheduledTransformationStepBuildOperationType.RESULT);
            return transform();
        }

        protected abstract TransformationSubject transform();
    }

}
