# MetricsProcessor
An annotation processor to make Prometheus metrics easier to use in Kotlin.

# How to use
Under the plugins section add kapt:
```
id "org.jetbrains.kotlin.kapt" version "${YourKotlinVersionHere}"
```

In the dependencies Section
```
    implementation 'com.doordash_oss.metricsprocessor:processor:2.0.0'
    kapt "com.doordash_oss.metricsprocessor:processor:2.0.0"
```

and outside the dependency block (basically anywhere not in any other block) add the following lines as well to help your ide:
```
sourceSets {
    main {
        java {
            srcDir "${buildDir.absolutePath}/generated/source/kaptKotlin/"
        }
    }
}
```

If you use a linter, ktlint for example, and run into an issue with ktlint complaining about the generated files add the following lines to your build.gradle:
```
ktlint {
    filter {
        exclude { element -> element.file.path.contains("generated/") }
    }
}
```

You may also want to enable annotation processing in Intellij:
![Intellij Screenshot](intellij.png)

See [Sample.kt](sample/src/main/kotlin/com/doordash_oss/metricsprocessor/Sample.kt) for an example of how to use it.

# License
```
Copyright 2021 DoorDash, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
```
