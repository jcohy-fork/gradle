/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution.serialization

import org.gradle.api.internal.GeneratedSubclasses
import org.gradle.instantexecution.serialization.StructuredMessage.Companion.build
import kotlin.reflect.KClass


typealias StructuredMessageBuilder = StructuredMessage.Builder.() -> Unit


fun IsolateContext.logPropertyWarning(action: String, message: StructuredMessageBuilder) {
    logPropertyFailure(action, PropertyFailure.Warning(trace, build(message)))
}


fun IsolateContext.logPropertyError(action: String, error: Throwable, message: StructuredMessageBuilder) {
    logPropertyFailure(action, PropertyFailure.Error(trace, build(message), error))
}


fun IsolateContext.logPropertyInfo(action: String, value: Any?) {
    logger.info("instant-execution > {}d {} with value {}", action, trace, value)
}


fun IsolateContext.logUnsupported(baseType: KClass<*>, actualType: Class<*>) {
    logPropertyWarning {
        text("cannot serialize object of type ")
        reference(GeneratedSubclasses.unpack(actualType))
        text(", a subtype of ")
        reference(baseType)
        text(", as these are not supported with instant execution.")
    }
}


fun IsolateContext.logUnsupported(baseType: KClass<*>) {
    logPropertyWarning {
        text("cannot serialize object of type ")
        reference(baseType)
        text(" as these are not supported with instant execution.")
    }
}


private
fun IsolateContext.logPropertyWarning(message: StructuredMessageBuilder) {
    val failure = PropertyFailure.Warning(trace, build(message))
    logger.warn("instant-execution > {}", failure.message)
    logPropertyFailure("serialize", failure)
}


private
fun IsolateContext.logPropertyFailure(action: String, failure: PropertyFailure) {
    logger.debug("instant-execution > failed to {} {} because {}", action, failure.trace, failure.message)
    failures.add(failure)
}
