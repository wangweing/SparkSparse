package sparse

import org.apache.spark.mllib.linalg.{Vectors, Vector}
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import scala.io.Source
import java.io._

class MatrixVectorIO(val filePath: String, val sc: SparkContext) {
    // Currently can only read in real, patter, integer matrices in general form.
    // @TODO: Add symmetric form to it
    // @Todo: Add other data types, i.e pattern, integer
    def readMatrix(name: String) = {
        val input = sc.textFile(filePath + name).map(x => x.split(' ').filter(y => y.length > 0))
        val meta = input.take(2)
        val matInfo = meta(0) // This has the matrix info like "sym", "real", "int", "pattern"
        val sizeInfo = meta(1) // This has the size of the matrix
        val size = (sizeInfo(0).toLong, sizeInfo(1).toLong)

        // The following codes depend on matrix market's matrix format
        val entryFiled = matInfo(3)
        val matFormat = matInfo(4)

        val sym: Boolean = if(matFormat == "general") false else true

        // This line of codes drops the first two lines of the file which is already captured 
        val filteredInput = input.mapPartitionsWithIndex{case (index, iter) => if(index == 0) iter.drop(2) else iter}
        
        entryFiled match {
            case "real" => 
                val entries = filteredInput.map(x => new MatrixEntry[Double](x(0).toLong - 1, x(1).toInt - 1, x(2).toDouble))
                new CoordinateMatrix[Double](entries, size._1, size._2, sym)
            case "pattern" => 
                val entries = filteredInput.map(x => new MatrixEntry[Boolean](x(0).toLong - 1, x(1).toInt - 1, true))
                new CoordinateMatrix[Boolean](entries, size._1, size._2, sym)
        }
    }

    // This routine saves result to a file, for future comparison use
    def saveResult(result: Vector, path: String) = {
        val vector = result.toArray
        val writer = new PrintWriter(new File(path))
        for( i <- 0 until result.size) {
            writer.write(vector(i).toString)
            writer.write("\n")
        }

        writer.close()
    }

    def readVector(name: String): Vector = {
        val entries = Source.fromFile(filePath + name).getLines.map(x => x.toDouble).toArray
        Vectors.dense(entries)
    }
}