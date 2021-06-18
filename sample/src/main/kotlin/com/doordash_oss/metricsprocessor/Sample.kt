/* Copyright 2021 DoorDash, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.doordash_oss.metricsprocessor

import java.time.Duration
import java.time.Instant

@Metric(MetricType.Counter, labels = ["label1", "label2"])
object Error

@Metric(MetricType.Histogram, "testHisto", "my help", ["histogram label"])
@ExponentialBuckets(1.0, 2.0, 16)
object Timing

@Metric(MetricType.Histogram, "testHisto2", "my help", ["histogram label"])
@LinearBuckets(1.0, 2.0, 16)
object Timing2

@Metric(MetricType.Histogram, "testHisto3", "my help", ["histogram label"])
@Buckets([1.0, 2.0, 3.0])
object Timing3

@Metric(MetricType.Histogram, "testHisto4", "my help", ["histogram label"])
object Timing4

@Metric(
    MetricType.Counter,
    "long_help_counter",
    "this is a really long help message and it may cause things to wrap because it is so long 1234567890 1234567890 1234567890 1234567890 1234567890"
)
object LongHelpCounter

@Metric(MetricType.Gauge, "testGauge", "my help")
object NumThreads

object Sample {

    @JvmStatic
    fun main(args: Array<String>) {
        val start = Instant.now()
        val metrics = Metrics.create()
        val metrics2 = Metrics.create() //safe to call twice, point to the same object!
        assert(metrics == metrics2)
        metrics.Error("label1Value", "label2Value").inc()
        metrics2.Error("label1Value", "label2Value").inc()
        metrics.Timing("labelValue").observe(10.0)
        val timer = metrics.Timing2("labelValue").startTimer()
        timer.observeDuration()
        metrics.Timing3("labelValue").observe(Duration.between(start, Instant.now()).toMillis().toDouble())
        metrics.Timing4("labelValue").observe(Duration.between(start, Instant.now()).toMillis().toDouble())
        metrics.NumThreads().set(100.0)
        println("Done!")
    }
}