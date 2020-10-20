/*
 * Copyright 2017 the original author or authors.
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
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionStructureVisitor;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * An artifact set containing transformed project artifacts.
 */
public class TransformedProjectArtifactSet implements ResolvedArtifactSet, FileCollectionInternal.Source {
    private final ComponentIdentifier componentIdentifier;
    private final ImmutableAttributes targetAttributes;
    private final Collection<TransformationNode> scheduledNodes;

    public TransformedProjectArtifactSet(
        ComponentIdentifier componentIdentifier,
        ResolvedArtifactSet delegate,
        ImmutableAttributes targetAttributes,
        Transformation transformation,
        ExtraExecutionGraphDependenciesResolverFactory dependenciesResolverFactory,
        TransformationNodeRegistry transformationNodeRegistry
    ) {
        this.componentIdentifier = componentIdentifier;
        this.targetAttributes = targetAttributes;
        this.scheduledNodes = transformationNodeRegistry.getOrCreate(delegate, transformation, dependenciesResolverFactory.create(componentIdentifier));
    }

    public ComponentIdentifier getOwnerId() {
        return componentIdentifier;
    }

    @Override
    public Completion startVisit(BuildOperationQueue<RunnableBuildOperation> actions, AsyncArtifactListener listener) {
        FileCollectionStructureVisitor.VisitType visitType = listener.prepareForVisit(this);
        if (visitType == FileCollectionStructureVisitor.VisitType.NoContents) {
            return visitor -> visitor.endVisitCollection(this);
        }

        List<ResolvableArtifact> result = new ArrayList<>(scheduledNodes.size());
        for (TransformationNode node : scheduledNodes) {
            node.executeIfNotAlready();
            for (File file : node.getTransformedSubject().get().getFiles()) {
                result.add(node.getInputArtifacts().transformedTo(file));
            }
        }
        return visitor -> {
            DisplayName displayName = Describables.of(componentIdentifier);
            for (ResolvableArtifact artifact : result) {
                visitor.visitArtifact(displayName, targetAttributes, artifact);
            }
            visitor.endVisitCollection(this);
        };
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        if (!scheduledNodes.isEmpty()) {
            context.add(new DefaultTransformationDependency(scheduledNodes));
        }
    }

    public Collection<TransformationNode> getScheduledNodes() {
        return scheduledNodes;
    }

    @Override
    public void visitLocalArtifacts(LocalArtifactVisitor visitor) {
        throw new UnsupportedOperationException("Should not be called.");
    }

    @Override
    public void visitExternalArtifacts(Action<ResolvableArtifact> visitor) {
        throw new UnsupportedOperationException("Should not be called.");
    }
}
