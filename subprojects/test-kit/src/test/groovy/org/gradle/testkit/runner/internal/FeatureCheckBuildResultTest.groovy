/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.testkit.runner.internal

import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.UnsupportedFeatureException
import org.gradle.util.GradleVersion
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class FeatureCheckBuildResultTest extends Specification {

    public static final GradleVersion SUPPORTED_GRADLE_VERSION = GradleVersion.version('2.5')
    public static final GradleVersion UNSUPPORTED_GRADLE_VERSION = GradleVersion.version('2.4')
    public static final String BUILD_RESULT_OUTPUT_UNSUPPORTED_FEATURE_MSG = "The version of Gradle you are using ($UNSUPPORTED_GRADLE_VERSION.version) does not capture build output with the GradleRunner. Support for this is available in Gradle $SUPPORTED_GRADLE_VERSION.version and all later versions."
    public static final String BUILD_RESULT_TASKS_UNSUPPORTED_FEATURE_MSG = "The version of Gradle you are using ($UNSUPPORTED_GRADLE_VERSION.version) does not capture executed tasks with the GradleRunner. Support for this is available in Gradle $SUPPORTED_GRADLE_VERSION.version and all later versions."

    String output = 'output'
    BuildTask successBuildResult = new DefaultBuildTask(':a', SUCCESS)
    BuildTask failedBuildResult = new DefaultBuildTask(':b', FAILED)
    def buildTasks = [successBuildResult, failedBuildResult]

    def "provides expected field values for supported Gradle version"() {
        FeatureCheckBuildResult buildResult = new FeatureCheckBuildResult(SUPPORTED_GRADLE_VERSION, output, buildTasks)

        expect:
        buildResult.output == 'output'
        buildResult.tasks == buildTasks
        buildResult.tasks(SUCCESS) == [successBuildResult]
        buildResult.tasks(FAILED) == [failedBuildResult]
        buildResult.taskPaths(SUCCESS) == [successBuildResult.path]
        buildResult.taskPaths(FAILED) == [failedBuildResult.path]
    }

    def "throws exception when getting output for unsupported Gradle Version"() {
        given:
        FeatureCheckBuildResult buildResult = new FeatureCheckBuildResult(UNSUPPORTED_GRADLE_VERSION, output, buildTasks)

        when:
        buildResult.output

        then:
        Throwable t = thrown(UnsupportedFeatureException)
        t.message == BUILD_RESULT_OUTPUT_UNSUPPORTED_FEATURE_MSG
    }

    def "throws exception when getting tasks for unsupported Gradle Version"() {
        given:
        FeatureCheckBuildResult buildResult = new FeatureCheckBuildResult(UNSUPPORTED_GRADLE_VERSION, output, buildTasks)

        when:
        buildResult.tasks

        then:
        Throwable t = thrown(UnsupportedFeatureException)
        t.message == BUILD_RESULT_TASKS_UNSUPPORTED_FEATURE_MSG
    }

    def "throws exception when getting tasks for outcome for unsupported Gradle Version"() {
        given:
        FeatureCheckBuildResult buildResult = new FeatureCheckBuildResult(UNSUPPORTED_GRADLE_VERSION, output, buildTasks)

        when:
        buildResult.tasks(SUCCESS)

        then:
        Throwable t = thrown(UnsupportedFeatureException)
        t.message == BUILD_RESULT_TASKS_UNSUPPORTED_FEATURE_MSG
    }

    def "throws exception when getting task paths for outcome for unsupported Gradle Version"() {
        given:
        FeatureCheckBuildResult buildResult = new FeatureCheckBuildResult(UNSUPPORTED_GRADLE_VERSION, output, buildTasks)

        when:
        buildResult.taskPaths(SUCCESS)

        then:
        Throwable t = thrown(UnsupportedFeatureException)
        t.message == BUILD_RESULT_TASKS_UNSUPPORTED_FEATURE_MSG
    }
}
