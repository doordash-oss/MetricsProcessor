package com.doordash_oss.metricsprocessor

import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.prometheus.client.CollectorRegistry
import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

object MetricType {
    const val Counter = "Counter"
    const val Gauge = "Gauge"
    const val Histogram = "Histogram"
}

annotation class Metric(
    val type: String,
    val name: String = "",
    val help: String = "no help set",
    val labels: Array<String> = [],
)


@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Buckets(
    val buckets: DoubleArray
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ExponentialBuckets(
    val start: Double,
    val factor: Double,
    val count: Int,
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class LinearBuckets(
    val start: Double,
    val width: Double,
    val count: Int,
)

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedOptions(MetricProcessor.optionName)
class MetricProcessor : AbstractProcessor() {
    override fun process(annotations: MutableSet<out TypeElement>?, roundEnv: RoundEnvironment?): Boolean {
        val generatedSourcesRoot: String =
            processingEnv.options[optionName].orEmpty()
        if (generatedSourcesRoot.isEmpty()) {
            processingEnv.messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Can't find the target directory for generated Kotlin files.",
            )
            return false
        }
        val allMetrics = roundEnv?.getElementsAnnotatedWith(classObj)
        checkForDups(allMetrics)
        val packageMap = allMetrics?.groupBy { element ->
                processingEnv.elementUtils.getPackageOf(element).toString()
            }

        packageMap?.entries?.forEach { (packageOf, elements) ->
            val file = File(generatedSourcesRoot)
            file.mkdir()
            val objectDef = TypeSpec.classBuilder("Metrics").apply {
                this.primaryConstructor(FunSpec.constructorBuilder().apply {
                    this.addParameter(ParameterSpec.builder(REGISTRY_VARIABLE, CollectorRegistry::class.java).apply {
                        this.defaultValue(CodeBlock.of("CollectorRegistry.defaultRegistry"))
                    }.build())
                    this.addModifiers(KModifier.PRIVATE)
                }.build())
                this.addType(
                    TypeSpec.companionObjectBuilder().apply {
                        this.addFunction(buildPublicConstructor(packageOf))
                        this.addProperty("lock", ANY, KModifier.PRIVATE)
                        this.addProperty("collectorMap",
                            MUTABLE_MAP.parameterizedBy(
                                listOf(
                                    CollectorRegistry::class.asClassName(),
                                    ClassName.bestGuess("$packageOf.Metrics")
                                )
                            )
                            , KModifier.PRIVATE    )
                        this.addInitializerBlock(CodeBlock.of("""
                            collectorMap = mutableMapOf()
                            lock = Any()
                            
                        """.trimIndent()))
                    }.build()
                )
                elements.forEach { element ->
                    this.addProperty(buildObj(element).build())
                    val funcs = buildFunc(packageOf, element)
                    funcs.forEach {
                        this.addFunction(it)
                    }
                }
                this.addInitializerBlock(getConstructors(elements))
            }
            FileSpec.builder(packageOf, "MetricDef")
                .addType(objectDef.build())
                .build()
                .writeTo(file)
        }
        return true
    }

    private fun checkForDups(metrics: Iterable<Element>?) {
        val bad = metrics?.groupBy {
            getPromName(it)
        }?.filter {
            it.value.count() >= 2
        }.orEmpty()
        if (bad.isNotEmpty()) {
            val names = bad.map {
                it.key
            }
            val msg = "Prometheus names must be unique! Duplicates found for names: $names"
            throw RuntimeException(msg)
        }
    }

    private fun buildPublicConstructor(packageOf: String): FunSpec {
       return FunSpec.builder("create").apply{
           this.addModifiers(KModifier.PUBLIC)
           this.addParameter(
               ParameterSpec.builder(REGISTRY_VARIABLE, CollectorRegistry::class.java).apply {
                   this.defaultValue(CodeBlock.of("CollectorRegistry.defaultRegistry"))
               }.build()
           )

           this.addCode("""
               val returnVal = synchronized(lock) { 
                   collectorMap.getOrPut($REGISTRY_VARIABLE) {
                     Metrics($REGISTRY_VARIABLE)
                   }
               }
               return returnVal
           """.trimIndent())
           this.returns(ClassName.bestGuess("$packageOf.Metrics"))
       }.build()
    }

    private fun baseFunction(
        element: Element,
        labels: Array<String>,
        call: String = "",
        returnFunc: Boolean = false
    ): String {
        val returnString = if (returnFunc) {
            "return"
        } else ""

        return "$returnString ${getName(element)}.labels(${labels.joinToString()})$call"
    }

    private fun addParameters(funSpec: FunSpec.Builder, parameters: Array<String>) {
        parameters.forEach { label ->
            funSpec.addParameter(
                label,
                String::class
            )
        }
    }

    private fun getName(element: Element): String {
        val annotation = element.getAnnotation(classObj)
        return "${element.simpleName}${annotation.type}"
    }

    private fun buildObj(element: Element): PropertySpec.Builder {
        val annotation = element.getAnnotation(classObj)
        val type = Class.forName("io.prometheus.client.${annotation.type}")
        return PropertySpec.builder(getName(element), type, KModifier.PRIVATE)
    }

    private fun getPromName(element: Element): String {
        val annotation = element.getAnnotation(classObj)
        val name =  annotation.name.ifEmpty {
            element.simpleName.toString()
        }.trim().replace(" ", "_")
        if (!promNameRegex.matches(name)) {
            throw RuntimeException("Metric name: $name, contains illegal characters, only alphanum and '_' are allowed")
        }
        return name
    }

    private fun getConstructors(elements: Iterable<Element>): CodeBlock {
        val builder = CodeBlock.builder()
        elements.forEach { element ->
            val annotation = element.getAnnotation(classObj)
            val extras = when {
                element.getAnnotation(Buckets::class.java) != null -> {
                    val bucketAnn = element.getAnnotation(Buckets::class.java)
                    ".buckets(${bucketAnn.buckets.joinToString()})"
                }
                element.getAnnotation(ExponentialBuckets::class.java) != null -> {
                    val bucketAnn = element.getAnnotation(ExponentialBuckets::class.java)
                    ".exponentialBuckets(${bucketAnn.start}, ${bucketAnn.factor}, ${bucketAnn.count})"
                }
                element.getAnnotation(LinearBuckets::class.java) != null -> {
                    val bucketAnn = element.getAnnotation(LinearBuckets::class.java)
                    ".linearBuckets(${bucketAnn.start}, ${bucketAnn.width}, ${bucketAnn.count})"
                }
                else -> {
                    if (annotation.type == "Histogram") {
                        ".buckets(*com.doordash_oss.metricsprocessor.MetricProcessor.DEFAULT_TIMER_HISTOGRAM_BUCKETS)"
                    } else {
                        ""
                    }
                }
            }
            val name = getPromName(element)
            val labels = cleanLabels(annotation.labels)
            builder.add(
                """
            ${getName(element)} = ${annotation.type}.build("$name",
                "${annotation.help.replace(" ", "·")}")
                .labelNames(${labels.joinToString { "\"$it\"" }})
                $extras
                .register($REGISTRY_VARIABLE)

            """.trimIndent()
            ).build()
        }
        return builder.build()
    }

    private fun buildFunc(packageOf: String, element: Element): Iterable<FunSpec> {
        val annotation = element.getAnnotation(classObj)
        val attachedLabels = cleanLabels(annotation.labels)
        val typeName = "io.prometheus.client.${annotation.type}"
        val childType = Class.forName("$typeName\$Child")
        val childBuilder = FunSpec.builder(element.simpleName.toString())
            .addModifiers(KModifier.PUBLIC)
            .returns(childType)
            .addCode(baseFunction(element, attachedLabels, returnFunc = true))
        addParameters(
            childBuilder, attachedLabels
        )
        return listOf(childBuilder.build())
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(classObj.canonicalName)
    }

    private fun cleanLabels(labels: Array<String>): Array<String> {
        return labels.map {
            it.trim().replace(" ", "_")
        }.toTypedArray()
    }

    companion object {
        val classObj = Metric::class.java
        const val optionName = "kapt.kotlin.generated"
        val promNameRegex = Regex("^[a-zA-Z0-9_]*$")

        val DEFAULT_TIMER_HISTOGRAM_BUCKETS = doubleArrayOf(
            0.002,
            0.004,
            0.006,
            0.008,
            0.01,
            0.02,
            0.04,
            0.06,
            0.08,
            0.1,
            0.2,
            0.4,
            0.6,
            0.8,
            1.0,
            1.2,
            1.4,
            1.6,
            1.8,
            2.0,
            2.5,
            3.0,
            3.5,
            4.0,
            4.5,
            5.0,
            5.5,
            6.0,
            6.5,
            7.0,
            7.5,
            8.0,
            8.5,
            9.0,
            9.5,
            10.0,
            15.0,
            20.0,
            25.0,
            30.0,
            35.0,
            40.0,
            45.0,
            50.0,
            55.0,
            60.0
        )
        const val REGISTRY_VARIABLE="collectorRegistry"
    }
}
