//> using target.scope test
// ^^ exclude from main compilation; run with: scala-cli run src/bench.scala src/*.scala -- <bench> <workspace>

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Microbenchmark harness ─────────────────────────────────────────────────

case class BenchResult(name: String, warmupRuns: Int, measuredRuns: Int,
                       meanMs: Double, medianMs: Double, p99Ms: Double, stddevMs: Double, minMs: Double, maxMs: Double)

def runBench(name: String, warmup: Int, iterations: Int)(body: => Unit): BenchResult =
  // Warmup
  for _ <- 1 to warmup do body

  // Measure
  val timings = Array.ofDim[Long](iterations)
  for i <- 0 until iterations do
    val t0 = System.nanoTime()
    body
    timings(i) = System.nanoTime() - t0

  val sorted = timings.sorted
  val mean = timings.map(_.toDouble).sum / iterations / 1_000_000.0
  val median = if iterations % 2 == 0 then
    (sorted(iterations / 2 - 1) + sorted(iterations / 2)) / 2.0 / 1_000_000.0
  else sorted(iterations / 2).toDouble / 1_000_000.0
  val p99Idx = math.min(((iterations - 1) * 0.99).ceil.toInt, iterations - 1)
  val p99 = sorted(p99Idx).toDouble / 1_000_000.0
  val variance = timings.map(t => math.pow(t.toDouble / 1_000_000.0 - mean, 2)).sum / iterations
  val stddev = math.sqrt(variance)
  val minMs = sorted.head.toDouble / 1_000_000.0
  val maxMs = sorted.last.toDouble / 1_000_000.0

  BenchResult(name, warmup, iterations, mean, median, p99, stddev, minMs, maxMs)

def printResult(r: BenchResult): Unit =
  println(f"  ${r.name}%-24s mean=${r.meanMs}%8.1f ms  median=${r.medianMs}%8.1f ms  p99=${r.p99Ms}%8.1f ms  stddev=${r.stddevMs}%6.1f ms  min=${r.minMs}%8.1f  max=${r.maxMs}%8.1f  (${r.warmupRuns}w/${r.measuredRuns}i)")

// ── Benchmark implementations ──────────────────────────────────────────────

def benchExtractSingle(workspace: Path, warmup: Int, iters: Int): BenchResult =
  // Find the largest .scala file
  val gitFiles = gitLsFiles(workspace)
  val largest = gitFiles.maxBy(gf => Files.size(gf.path))
  println(s"  target: ${workspace.relativize(largest.path)} (${Files.size(largest.path) / 1024} KB)")
  runBench("extract-single", warmup, iters) {
    extractSymbols(largest.path)
  }

def benchExtractBatch(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val gitFiles = gitLsFiles(workspace).take(100)
  println(s"  target: ${gitFiles.size} files (sequential)")
  runBench("extract-batch-seq", warmup, iters) {
    gitFiles.foreach(gf => extractSymbols(gf.path))
  }

def benchExtractBatchParallel(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val gitFiles = gitLsFiles(workspace).take(100)
  println(s"  target: ${gitFiles.size} files (parallel)")
  runBench("extract-batch-par", warmup, iters) {
    gitFiles.asJava.parallelStream().forEach(gf => extractSymbols(gf.path))
  }

def benchBloomBuild(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val gitFiles = gitLsFiles(workspace)
  val largest = gitFiles.maxBy(gf => Files.size(gf.path))
  val source = Files.readString(largest.path)
  println(s"  target: ${workspace.relativize(largest.path)} (${source.length} chars)")
  runBench("bloom-build", warmup, iters) {
    buildBloomFilterFromSource(source)
  }

def benchPersistenceSave(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val idx = WorkspaceIndex(workspace, needBlooms = true)
  idx.index()
  println(s"  target: full index (${idx.fileCount} files, ${idx.symbols.size} symbols)")
  // Access private indexedFiles via re-indexing trick — just time the save
  val idxPath = IndexPersistence.indexPath(workspace)
  runBench("persistence-save", warmup, iters) {
    // Re-save existing index
    idx.index() // re-index to get indexedFiles populated
  }

def benchPersistenceLoad(workspace: Path, warmup: Int, iters: Int): BenchResult =
  // Ensure index exists
  val idx = WorkspaceIndex(workspace, needBlooms = true)
  idx.index()
  println(s"  target: index.bin (${Files.size(IndexPersistence.indexPath(workspace)) / 1024} KB)")
  runBench("persistence-load", warmup, iters) {
    IndexPersistence.load(workspace, loadBlooms = true)
  }

def benchPersistenceLoadNoBlooms(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val idx = WorkspaceIndex(workspace, needBlooms = true)
  idx.index()
  runBench("persistence-load-nobloom", warmup, iters) {
    IndexPersistence.load(workspace, loadBlooms = false)
  }

def benchSearch(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val idx = WorkspaceIndex(workspace, needBlooms = false)
  idx.index()
  println(s"  query: \"Compiler\"")
  runBench("search", warmup, iters) {
    idx.search("Compiler")
  }

def benchRefs(workspace: Path, warmup: Int, iters: Int): BenchResult =
  val idx = WorkspaceIndex(workspace, needBlooms = true)
  idx.index()
  println(s"  query: \"Phase\"")
  runBench("refs", warmup, iters) {
    idx.findReferences("Phase")
  }

def benchIndexBuild(workspace: Path, warmup: Int, iters: Int): BenchResult =
  // Cold index to measure just the map building phase
  val idx = WorkspaceIndex(workspace, needBlooms = true)
  println(s"  target: full index + map build")
  runBench("index-cold", warmup, math.min(iters, 5)) {
    java.nio.file.Files.deleteIfExists(IndexPersistence.indexPath(workspace))
    idx.index()
  }

// ── Main entry point ───────────────────────────────────────────────────────

@main def bench(args: String*): Unit =
  val argList = args.toList

  if argList.isEmpty || argList.contains("--help") then
    println("""scalex microbenchmark harness
      |
      |Usage: scala-cli run src/bench.scala src/*.scala -- <benchmark> <workspace> [options]
      |
      |Benchmarks:
      |  extract-single     extractSymbols on one large file
      |  extract-batch      extractSymbols on 100 files (sequential vs parallel)
      |  bloom-build        buildBloomFilterFromSource on a large source
      |  persistence-save   IndexPersistence.save on full index
      |  persistence-load   IndexPersistence.load with/without blooms
      |  search             WorkspaceIndex.search("Compiler") warm
      |  refs               findReferences("Phase") warm
      |  index-cold         Full cold index (including map building)
      |  all                Run all benchmarks
      |
      |Options:
      |  --warmup N         Warmup iterations (default: 5)
      |  --iterations N     Measured iterations (default: 20)
      |""".stripMargin)
    return

  val warmup = argList.indexOf("--warmup") match
    case -1 => 5
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(5)
  val iterations = argList.indexOf("--iterations") match
    case -1 => 20
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(20)

  val flagsWithArgs = Set("--warmup", "--iterations")
  val cleanArgs = argList.filterNot(a => a.startsWith("--") || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && flagsWithArgs.contains(argList(prev))
  })

  val benchName = cleanArgs.headOption.getOrElse("all")
  val workspacePath = cleanArgs.lift(1).getOrElse(".")
  val workspace = Path.of(workspacePath).toAbsolutePath.normalize

  if !Files.isDirectory(workspace) then
    System.err.println(s"Error: $workspace is not a directory")
    System.exit(1)

  println(s"Workspace: $workspace")
  println(s"Config: warmup=$warmup, iterations=$iterations")
  println()

  def run(name: String): Unit =
    println(s"[$name]")
    val result = name match
      case "extract-single" => benchExtractSingle(workspace, warmup, iterations)
      case "extract-batch" =>
        val r1 = benchExtractBatch(workspace, warmup, iterations)
        printResult(r1)
        benchExtractBatchParallel(workspace, warmup, iterations)
      case "bloom-build" => benchBloomBuild(workspace, warmup, iterations)
      case "persistence-save" => benchPersistenceSave(workspace, warmup, iterations)
      case "persistence-load" =>
        val r1 = benchPersistenceLoad(workspace, warmup, iterations)
        printResult(r1)
        benchPersistenceLoadNoBlooms(workspace, warmup, iterations)
      case "search" => benchSearch(workspace, warmup, iterations)
      case "refs" => benchRefs(workspace, warmup, iterations)
      case "index-cold" => benchIndexBuild(workspace, warmup, math.min(iterations, 5))
      case _ =>
        println(s"  Unknown benchmark: $name")
        return
    printResult(result)
    println()

  if benchName == "all" then
    val allBenches = List("extract-single", "extract-batch", "bloom-build",
                          "persistence-load", "search", "refs", "index-cold")
    allBenches.foreach(run)
  else
    run(benchName)

  println("Done.")
