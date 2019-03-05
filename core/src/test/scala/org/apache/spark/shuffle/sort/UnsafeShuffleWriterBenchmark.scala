/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.shuffle.sort

import org.apache.spark.SparkConf
import org.apache.spark.benchmark.Benchmark
import org.apache.spark.util.Utils

/**
 * Benchmark to measure performance for aggregate primitives.
 * {{{
 *   To run this benchmark:
 *   1. without sbt: bin/spark-submit --class <this class> <spark sql test jar>
 *   2. build/sbt "sql/test:runMain <this class>"
 *   3. generate result: SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain <this class>"
 *      Results will be written to "benchmarks/<this class>-results.txt".
 * }}}
 */
object UnsafeShuffleWriterBenchmark extends ShuffleWriterBenchmarkBase {

  private val shuffleHandle: SerializedShuffleHandle[String, String] =
    new SerializedShuffleHandle[String, String](0, 0, this.dependency)

  private val MIN_NUM_ITERS = 10
  private val DATA_SIZE_SMALL = 1000
  private val DATA_SIZE_LARGE =
    PackedRecordPointer.MAXIMUM_PAGE_SIZE_BYTES/2/DEFAULT_DATA_STRING_SIZE

  def getWriter(transferTo: Boolean): UnsafeShuffleWriter[String, String] = {
    val conf = new SparkConf(loadDefaults = false)
    conf.set("spark.file.transferTo", String.valueOf(transferTo))

    new UnsafeShuffleWriter[String, String](
      blockManager,
      blockResolver,
      taskMemoryManager,
      shuffleHandle,
      0,
      taskContext,
      conf,
      taskContext.taskMetrics().shuffleWriteMetrics
    )
  }

  def writeBenchmarkWithSmallDataset(): Unit = {
    val size = DATA_SIZE_SMALL
    val array = createDataInMemory(size)
    val benchmark = new Benchmark("UnsafeShuffleWriter without spills",
      size,
      minNumIters = MIN_NUM_ITERS,
      output = output)
    addBenchmarkCase(benchmark, "small dataset without spills") { timer =>
      val writer = getWriter(false)
      timer.startTiming()
      writer.write(array.iterator)
      timer.stopTiming()
      assert(tempFilesCreated.length == 1) // The single temp file is for the temp index file
    }
    benchmark.run()
  }

  def writeBenchmarkWithSpill(): Unit = {
    val size = DATA_SIZE_LARGE
    val tempDataFile = createDataOnDisk(size)
    val benchmark = new Benchmark("UnsafeShuffleWriter with spills",
      size,
      minNumIters = MIN_NUM_ITERS,
      output = output,
      outputPerIteration = true)
    addBenchmarkCase(benchmark, "without transferTo") { timer =>
      val shuffleWriter = getWriter(false)
      Utils.tryWithResource(DataIterator(inputFile = tempDataFile, DEFAULT_DATA_STRING_SIZE)) {
        iterator =>
          timer.startTiming()
          shuffleWriter.write(iterator)
          timer.stopTiming()
      }
      assert(tempFilesCreated.length == 7)
    }
    addBenchmarkCase(benchmark, "with transferTo") { timer =>
      val shuffleWriter = getWriter(true)
      Utils.tryWithResource(DataIterator(inputFile = tempDataFile, DEFAULT_DATA_STRING_SIZE)) {
        iterator =>
          timer.startTiming()
          shuffleWriter.write(iterator)
          timer.stopTiming()
      }
      assert(tempFilesCreated.length == 7)
    }
    benchmark.run()
    tempDataFile.delete()
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    runBenchmark("UnsafeShuffleWriter write") {
      writeBenchmarkWithSmallDataset()
      writeBenchmarkWithSpill()
    }
  }
}
