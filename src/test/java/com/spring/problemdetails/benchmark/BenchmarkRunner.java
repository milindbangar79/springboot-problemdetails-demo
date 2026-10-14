package com.spring.problemdetails.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Entry point for running all JMH benchmarks.
 *
 * Run from IDE: execute main() directly.
 *
 * Run from Maven (after mvn test-compile):
 *   mvn exec:java -Dexec.mainClass=com.spring.problemdetails.benchmark.BenchmarkRunner \
 *                 -Dexec.classpathScope=test
 *
 * Results are written to target/benchmark-results.json
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        String outputFile = System.getProperty("benchmark.output", "target/benchmark-results.json");
        Options opts = new OptionsBuilder()
                .include(EmployeeCrudBenchmark.class.getSimpleName())
                .include(CacheEffectBenchmark.class.getSimpleName())
                .include(ThreadModelBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(outputFile)
                .build();
        new Runner(opts).run();
    }
}
