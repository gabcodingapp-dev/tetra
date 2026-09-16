#!/bin/sh
#
# Copyright © 2015-2021 the original authors (see the Gradle distribution).
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

##############################################################################
#  Generic Gradle wrapper with fallback to system gradle when the wrapper
#  JVM bootstrap is unavailable.
##############################################################################

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd || pwd)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

require_tool() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: $1 is required but was not found on PATH." >&2
        exit 1
    }
}

if [ -f "$CLASSPATH" ]; then
    require_tool java
    # shellcheck disable=SC2086
    exec java -Xmx64m -Dfile.encoding=UTF-8 \
        -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
else
    require_tool gradle
    exec gradle "$@"
fi