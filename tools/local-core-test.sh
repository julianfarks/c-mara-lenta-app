#!/usr/bin/env bash
# Compila y ejecuta las pruebas del módulo :core SIN red, usando el compilador de Kotlin
# y JUnit que ya trae Gradle. Útil para comprobar la lógica fuera de Android Studio / CI.
# Uso: tools/local-core-test.sh [directorio_lib_de_gradle]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${1:-/opt/gradle-8.14.3/lib}"
OUT="${TMPDIR:-/tmp}/farks-core-build"
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"

COMPILER_CP="$LIB/kotlin-compiler-embeddable-2.0.21.jar:$LIB/kotlin-stdlib-2.0.21.jar:$LIB/kotlin-script-runtime-2.0.21.jar:$LIB/kotlin-reflect-2.0.21.jar:$LIB/kotlin-daemon-embeddable-2.0.21.jar:$LIB/trove4j-1.0.20200330.jar:$LIB/annotations-24.0.1.jar:$LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
STDLIB="$LIB/kotlin-stdlib-2.0.21.jar"
JUNIT="$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar"

kotlinc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "$@"; }

echo "== Compilando main =="
kotlinc -jvm-target 17 -cp "$STDLIB" -d "$OUT/main" $(find "$ROOT/core/src/main" -name '*.kt')
echo "== Compilando tests =="
kotlinc -jvm-target 17 -cp "$STDLIB:$JUNIT:$OUT/main" -d "$OUT/test" $(find "$ROOT/core/src/test" -name '*.kt')

CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
echo "== Ejecutando: $CLASSES =="
java -cp "$STDLIB:$JUNIT:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES
