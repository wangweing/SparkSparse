package sparse

import breeze.linalg.{DenseMatrix => BDM}
import breeze.linalg._

import org.apache.spark
import org.apache.spark.annotation.Experimental
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.{Vectors,Vector}
import org.apache.spark.mllib.linalg.distributed._

/**
 * :: Experimental ::
 * Represents an entry in an distributed matrix.
 * @param i row index
 * @param j column index
 * @param value value of the entry
 */
@Experimental
case class MatrixEntry(i: Long, j: Long, value: Double)

/**
 * :: Experimental ::
 * Represents a matrix in coordinate format.
 *
 * @param E type parameter, could be Double, Int Boolean
 * @param entries matrix entries
 * @param nRows number of rows. A non-positive value means unknown, and then the number of rows will
 *              be determined by the max row index plus one.
 * @param nCols number of columns. A non-positive value means unknown, and then the number of
 *              columns will be determined by the max column index plus one.
 * @param sym A boolean parameter telling us whether the matrix is symmetric, if it is, only "tril" will be stored
 */
@Experimental
class CoordinateMatrix(
    val entries: RDD[MatrixEntry],
    private var nRows: Long,
    private var nCols: Long,
    private val sym: Boolean = false,
    private val partNum: Int = 1) extends DistributedMatrix {

  /** Alternative constructor leaving matrix dimensions to be determined automatically. */
  def this(entries: RDD[MatrixEntry]) = this(entries, 0L, 0L)
  def this(entries: RDD[MatrixEntry], sym: Boolean) = this(entries, 0L, 0L, sym)
  def this(entries: RDD[MatrixEntry], partNum: Int) = this(entries, 0L, 0L, false, partNum)
  def this(entries: RDD[MatrixEntry], sym: Boolean, partNum: Int) = this(entries, 0L, 0L, sym, partNum)

  // Transform the entries to RDD[(index, SparseVector)] as a row index and the row
  val rowForm = toSparseRowVectors(entries.map(entry => (entry.i, (entry.j.toInt, entry.value)))).persist
  val colForm = toSparseRowVectors(entries.map(entry => (entry.j, (entry.i.toInt, entry.value)))).persist

  private def toSparseRowVectors(matrix: RDD[(Long, (Int, Double))]): RDD[(Long, SparseVector[Double])] = {
    matrix.groupByKey(new spark.HashPartitioner(partNum)).map{
      case(key, value) => 
        val sortedPairs = value.toArray.sortWith((v1, v2) => v1._1 < v2._1)
        val len = sortedPairs.size
        val index = new Array[Int](len)
        val elems = new Array[Double](len)

        for( i <- 0 until len) {
          index(i) = sortedPairs(i)._1
          elems(i) = sortedPairs(i)._2
        }

        val sp = new SparseVector(index, elems, len, numCols.toInt)
        (key, sp)
    }
  }

  //val rowForm = entries.map(entry => (entry.i, (entry.j.toInt, entry.value))).partitionBy(new spark.HashPartitioner(partNum)).persist

  /** Gets or computes the number of columns. */
  override def numCols(): Long = {
    if (nCols <= 0L) {
      computeSize()
    }
    nCols
  }

  /** Gets or computes the number of rows. */
  override def numRows(): Long = {
    if (nRows <= 0L) {
      computeSize()
    }
    nRows
  }

  /**
    Multiplies a Coordinate matrix with a local vector.
    @param matrix: The coordinate matrix to be multiplied with
    @param vector: The local vector, could be multiplied on the left or right
    @param sc: The SparkContext that handles parallel computations
    @param(Optional) numTasks: THe number of tasks used to reduce the result, default is 4
    @Param(Optional) "trans": A boolean variable indicating the "trans" of multiplication, by default is "false", meaning on the right
    x = A * v (if trans = false)
    x = A' * v (if trans = true)
  */
  def multiply(vector: Vector, sc: SparkContext, trans: Boolean = false, numTasks: Int = 4): Vector = {
    require(numRows < Int.MaxValue && numCols < Int.MaxValue, "Cannot multiply this matrix because size is too large!")
    require(vector.size == numCols.toInt, "Matrix vector size mismatch!")
    val copies = sc.broadcast(vector.toArray)
    val v = DenseVector(copies.value)

    if(!sym){
      val vectorArray = if (trans) colForm.map{case(ind, sp) => (ind, sp dot v)}.collect
                        else rowForm.map{case(ind, sp) => (ind, sp dot v)}.collect
      if (trans) SparseUtility.transform(vectorArray, numCols.toInt)
      else SparseUtility.transform(vectorArray, numRows.toInt)
    }else{
      // Notice that a symmetric matrix must be square
      // So we compute Ay + (A - diag(A))^Ty
      val first = rowForm.map{case(ind, sp) => (ind, sp dot v)}.collect
      val second = colForm.map{case(ind, sp) => (ind, sp.dot(v) - sp(ind.toInt) * v(ind.toInt))}.collect

      val result = first ++ second
      SparseUtility.transform(result, numRows.toInt)
    }
  }

  /** Converts to IndexedRowMatrix. The number of columns must be within the integer range. */
  def toIndexedRowMatrix(): IndexedRowMatrix = {
    val nl = numCols()
    if (nl > Int.MaxValue) {
      sys.error("Cannot convert to a row-oriented format because the number of columns $nl is " +
        "too large.")
    }
    val n = nl.toInt

    // We have to cast all the entries into doubles again
    val entriesAsDoubles: RDD[(Long, Int, Double)] = entries.map {entry =>
      (entry.i, entry.j.toInt, entry.value)
    }

    val indexedRows = entriesAsDoubles.map(entry => (entry._1, (entry._2, entry._3)))
    .groupByKey().map { 
      case (i, vectorEntries) => IndexedRow(i, Vectors.sparse(n, vectorEntries.toSeq))
    }

    new IndexedRowMatrix(indexedRows, numRows(), n)
  }

  /**
   * Converts to RowMatrix, dropping row indices after grouping by row index.
   * The number of columns must be within the integer range.
   */
  def toRowMatrix(): RowMatrix = {
    this.toIndexedRowMatrix().toRowMatrix()
  }

  /** Determines the size by computing the max row/column index. */
  private def computeSize() {
    // Reduce will throw an exception if `entries` is empty.
    val (m1, n1) = entries.map(entry => (entry.i, entry.j)).reduce { case ((i1, j1), (i2, j2)) =>
      (math.max(i1, i2), math.max(j1, j2))
    }
    // There may be empty columns at the very right and empty rows at the very bottom.
    nRows = math.max(nRows, m1 + 1L)
    nCols = math.max(nCols, n1 + 1L)
  }

  /**
   * Also make this toBreeze public because it has to be at least private to mllib, which I cannot
   * restrict to.
   */
  override def toBreeze(): BDM[Double] = {
    val m = numRows().toInt
    val n = numCols().toInt
    val mat = BDM.zeros[Double](m, n)
    entries.collect().foreach { case MatrixEntry(i, j, value) =>
      mat(i.toInt, j.toInt) = value
      if (sym) mat(j.toInt, i.toInt) = value
    }
    mat
  }
}