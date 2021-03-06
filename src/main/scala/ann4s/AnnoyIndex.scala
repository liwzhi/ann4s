package ann4s

import java.io.FileOutputStream

import ann4s.Functions._
import org.apache.spark.proxy.BoundedPriorityQueue

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class AnnoyIndex(dim: Int, metric: Metric, random: Random) {

  def this(f: Int, random: Random) = this(f, Angular, random)

  def this(f: Int, metric: Metric) = this(f, metric, RandRandom)

  def this(f: Int) = this(f, Angular, RandRandom)

  private val childrenCapacity: Int = metric.childrenCapacity(dim)

  private var verbose0: Boolean = false

  private var nodes: NodeStorage = null

  private val roots = new ArrayBuffer[Int]()

  private var loaded: Boolean = false

  private var nItems: Int = 0

  private var nNodes: Int = 0

  reinitialize()

  def getBufferType: String = nodes.bufferType

  def getDim: Int = dim

  def addItem(item: Int, w: Array[Float]): Unit = {
    ensureSize(item + 1)
    val n = getMutableNode(item)

    n.setChildren(0, 0)
    n.setChildren(1, 0)
    n.setNDescendants(1)
    n.setVector(w)

    n.commit()

    if (item >= nItems)
      nItems = item + 1
  }

  def build(q: Int): Unit = {
    require(q > 0)
    require(!loaded, "You can't build a loaded index")

    nNodes = nItems
    val indices = new ArrayBuffer(nItems) ++= (0 until nItems)
    while (roots.length < q) {
      if (verbose0) showUpdate("pass %d...\n", roots.length)
      roots += makeTree(indices)
    }

    // Also, copy the roots into the last segment of the array
    // This way we can load them faster without reading the whole file
    ensureSize(nNodes + roots.length)
    roots.zipWithIndex.foreach { case (root, i) =>
      val n = getMutableNode(nNodes + i)
      n.copyFrom(getImmutableNode(root))
      n.commit()
    }
    nNodes += roots.length
    nodes.flip()

    if (verbose0) showUpdate("has %d nodes\n", nNodes)
  }

  private def makeTree(indices: ArrayBuffer[Int]): Int = {
    if (indices.length == 1)
      return indices(0)

    if (indices.length <= childrenCapacity) {
      ensureSize(nNodes + 1)
      val item = nNodes
      nNodes += 1
      val m = getMutableNode(item)
      m.setNDescendants(indices.length)
      m.setAllChildren(indices.toArray)
      m.commit()
      return item
    }

    val children = indices.flatMap(i => getNodeOption(i).map(i -> _))

    val m = nodes.newNode
    // TODO: Distribute
    metric.createSplit(children.map(_._2), dim, random, m)

    val childrenIndices = Array.fill(2) {
      new ArrayBuffer[Int](indices.length)
    }

    val vectorBuffer = new Array[Float](dim)
    val sideBuffer = new Array[Float](dim)
    children.foreach { case (i, child) =>
      val side = if (metric.side(m, child.getVector(vectorBuffer), random, sideBuffer)) 1 else 0
      childrenIndices(side) += i
    }

    // If we didn't find a hyperplane, just randomize sides as a last option
    while (childrenIndices(0).isEmpty || childrenIndices(1).isEmpty) {
      if (verbose0 && indices.length > 100000)
        showUpdate("Failed splitting %d items\n", indices.length)

      childrenIndices(0).clear()
      childrenIndices(1).clear()

      // Set the vector to 0.0
      m.setValue(0f)

      indices.foreach { i =>
        // Just randomize...
        childrenIndices(if (random.flip()) 1 else 0) += i
      }
    }

    val flip = if (childrenIndices(0).length > childrenIndices(1).length) 1 else 0

    m.setNDescendants(indices.length)
    m.setChildren(0 ^ flip, makeTree(childrenIndices(0 ^ flip)))
    m.setChildren(1 ^ flip, makeTree(childrenIndices(1 ^ flip)))

    ensureSize(nNodes + 1)
    val item = nNodes
    nNodes += 1
    val n = getMutableNode(item)
    n.copyFrom(m)
    n.commit()
    item
  }

  def save(filename: String, reload: Boolean = true): Boolean = {
    nodes match {
      case heapNodes: HeapNodeStorage =>
        heapNodes.prepareToWrite()
        val fs = new FileOutputStream(filename).getChannel
        fs.write(heapNodes.underlying)
        fs.close()
      case _ =>
    }
    if (reload) {
      unload()
      load(filename)
    } else {
      true
    }
  }

  def unload(): Unit = {
    nodes match {
      case mappedNodes: MappedNodeStorage =>
        mappedNodes.close()
      case _ =>
    }
    reinitialize()
    if (verbose0) showUpdate("unloaded\n")
  }

  def load(filename: String, useHeap: Boolean = false): Boolean = {
    val nodesOnFile = new MappedNodeStorage(dim, filename, metric)
    nodes = nodesOnFile
    nNodes = nodes.numNodes
    var m = -1
    var i = nNodes - 1
    while (i >= 0) {
      val k = getImmutableNode(i).getNDescendants
      if (m == -1 || k == m) {
        roots += i
        m = k
      } else {
        i = 0 // break
      }
      i -= 1
    }

    if (roots.length > 1 && getImmutableNode(roots.head).getChildren(0) == getImmutableNode(roots.last).getChildren(0)) {
      roots -= roots.last // pop_back
    }
    loaded = true
    nItems = m

    if (useHeap) {
      val nodesOnHeap = new HeapNodeStorage(dim, nNodes, metric)
      nodesOnFile.underlying.rewind()
      nodesOnHeap.underlying.put(nodesOnFile.underlying)
      nodes = nodesOnHeap
      nodesOnFile.close()
    }

    if (verbose0) showUpdate("found %d roots with degree %d\n", roots.length, m)
    true
  }

  def verbose(v: Boolean): Unit = this.verbose0 = v

  def getNItems: Int = nItems

  def getItem(item: Int): Array[Float] = getImmutableNode(item).getVector(new Array[Float](dim))

  def getNnsByItem(item: Int, n: Int): Array[(Int, Float)] = getNnsByItem(item, n, -1)

  def getNnsByItem(item: Int, n: Int, k: Int): Array[(Int, Float)] = getAllNns(getItem(item), n, k)

  def getNnsByVector(w: Array[Float], n: Int): Array[(Int, Float)] = getNnsByVector(w, n, -1)

  def getNnsByVector(w: Array[Float], n: Int, k: Int): Array[(Int, Float)] = getAllNns(w, n, k)

  val ord = new Ordering[(Int, Float)]{
    def compare(x: (Int, Float), y: (Int, Float)): Int = {
      val compare1 = Ordering[Float].compare(x._2, y._2)
      if (compare1 != 0) return compare1
      val compare2 = Ordering[Int].compare(x._1, y._1)
      if (compare2 != 0) return compare2
      0
    }
  }

  private def getAllNns(v: Array[Float], n: Int, k: Int): Array[(Int, Float)] = {
    val vectorBuffer = new Array[Float](dim)
    val searchK = if (k == -1) n * roots.length else k

    val q = new mutable.PriorityQueue[(Float, Int)] ++= roots.map(Float.PositiveInfinity -> _)

    var searched = 0
    val nns = new ArrayBuffer[Int](searchK)
    val childrenBuffer = new Array[Int](childrenCapacity)
    while (searched < searchK && q.nonEmpty) {
      val (d, i) = q.dequeue()
      val nd = getImmutableNode(i)
      val nDescendants = nd.getNDescendants
      if (nDescendants == 1 && i < nItems) {
        nns += i
        searched += 1
      } else if (nDescendants <= childrenCapacity) {
        nd.getAllChildren(childrenBuffer)
        var j = 0
        while (j < nDescendants) {
          nns += childrenBuffer(j)
          searched += 1
          j += 1
        }
      } else {
        val margin = metric.margin(nd, v, vectorBuffer)
        q += math.min(d, +margin) -> nd.getChildren(1)
        q += math.min(d, -margin) -> nd.getChildren(0)
      }
    }

    val boundedQueue = new BoundedPriorityQueue[(Int, Float)](n)(ord.reverse)
    val seen = new mutable.BitSet
    for (j <- nns) {
      if (!seen(j)) {
        boundedQueue += j -> metric.distance(v, getImmutableNode(j).getVector(vectorBuffer))
        seen += j
      }
    }

    val result = boundedQueue.toArray
    var i = 0
    while (i < result.length) {
      result(i) = (result(i)._1, metric.normalizeDistance(result(i)._2))
      i += 1
    }
    java.util.Arrays.sort(result, 0, result.length, ord)
    result
  }

  def getDistance(i: Int, j: Int): Float = {
    metric.distance(getImmutableNode(i).getVector(new Array[Float](dim)), getImmutableNode(j).getVector(new Array[Float](dim)))
  }

  private def getImmutableNode(item: Int): Node =
    nodes(item, readonly = true)

  private def getMutableNode(item: Int): Node =
    nodes(item, readonly = false)

  private def getNodeOption(item: Int): Option[Node] = {
    val n = nodes(item, readonly = true)
    if (n.getNDescendants == 0) None else Some(n)
  }

  private def reinitialize(): Unit = {
    nodes = null
    loaded = false
    nItems = 0
    nNodes = 0
    roots.clear()
  }

  private def ensureSize(n: Int): Unit = {
    if (nodes == null)
      nodes = new HeapNodeStorage(dim, 0, metric)
    nodes.ensureSize(n, verbose0)
  }

}

