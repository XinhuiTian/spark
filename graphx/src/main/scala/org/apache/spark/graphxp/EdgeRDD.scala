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

package org.apache.spark.graphxp

import scala.language.existentials
import scala.reflect.ClassTag

import org.apache.spark._

import org.apache.spark.graphxp.impl.EdgePartitionBuilder
import org.apache.spark.graphxp.util.collection
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

/**
 * `EdgeRDD[ED, VD]` extends `RDD[Edge[ED]]` by storing the edges in columnar format on each
 * partition for performance. It may additionally store the vertex attributes associated with each
 * edge to provide the triplet view. Shipping of the vertex attributes is managed by
 * `impl.ReplicatedVertexView`.
 */
abstract class EdgeRDD[ED](
    sc: SparkContext,
    deps: Seq[Dependency[_]]) extends RDD[Edge[ED]](sc, deps) {

  // scalastyle:off structural.type
  private[graphxp] def partitionsRDD: RDD[(PartitionID, impl.EdgePartition[ED, VD])] forSome { type VD }
  // scalastyle:on structural.type

  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  override def compute(part: Partition, context: TaskContext): Iterator[Edge[ED]] = {
    val p = firstParent[(PartitionID, impl.EdgePartition[ED, _])].iterator(part, context)
    if (p.hasNext) {
      p.next()._2.iterator.map(_.copy())
    } else {
      Iterator.empty
    }
  }

  /**
   * Map the values in an edge partitioning preserving the structure but changing the values.
   *
   * @tparam ED2 the new edge value type
   * @param f the function from an edge to a new edge value
   * @return a new EdgeRDD containing the new edge values
   */
  def mapValues[ED2: ClassTag](f: Edge[ED] => ED2): EdgeRDD[ED2]

  /**
   * Reverse all the edges in this RDD.
   *
   * @return a new EdgeRDD containing all the edges reversed
   */
  def reverse: EdgeRDD[ED]

  /**
   * Inner joins this EdgeRDD with another EdgeRDD, assuming both are partitioned using the same
   * [[PartitionStrategy]].
   *
   * @param other the EdgeRDD to join with
   * @param f the join function applied to corresponding values of `this` and `other`
   * @return a new EdgeRDD containing only edges that appear in both `this` and `other`,
   *         with values supplied by `f`
   */
  def innerJoin[ED2: ClassTag, ED3: ClassTag]
      (other: EdgeRDD[ED2])
      (f: (VertexId, VertexId, ED, ED2) => ED3): EdgeRDD[ED3]

  def getInDegreeCounts: RDD[(Int, Iterator[(VertexId, Int)])] = {
    partitionsRDD.map(iter => (iter._1, iter._2.countingInEdges))
  }

  def maxInDegreeCounts: RDD[(VertexId, Int)] = {
    val indegrees = getInDegreeCounts.mapPartitions { partIter =>
      val part = partIter.next()
      part._2
    }.partitionBy(new HashPartitioner(getNumPartitions))

    indegrees.mapPartitions { partIter =>
      val maxInDegrees = new collection.GraphXPrimitiveKeyOpenHashMap[VertexId, Int]
      partIter.foreach { inDegree =>
        maxInDegrees.changeValue(inDegree._1, inDegree._2,
          { v :Int => if (inDegree._2 > v) inDegree._2 else v })

      }
      maxInDegrees.iterator
    }
  }

  def maxOutDegreeCounts: RDD[(VertexId, Int)] = {
    val outdegrees = getOutDegreeCounts.mapPartitions { partIter =>
      val part = partIter.next()
      part._2
    }.partitionBy(new HashPartitioner(getNumPartitions))

    // outdegrees.foreach(println)

    outdegrees.mapPartitions { partIter =>
      val maxOutDegrees = new collection.GraphXPrimitiveKeyOpenHashMap[VertexId, Int]
      partIter.foreach { outDegree =>
        maxOutDegrees.changeValue(outDegree._1, outDegree._2,
        { v :Int => if (outDegree._2 > v) outDegree._2 else v })
      }
      maxOutDegrees.iterator
    }
  }

  def getOutDegreeCounts: RDD[(Int, Iterator[(Int, Int)])] = {
    partitionsRDD.map(iter => (iter._1, iter._2.countingOutEdges))
  }

  /**
   * Changes the target storage level while preserving all other properties of the
   * EdgeRDD. Operations on the returned EdgeRDD will preserve this storage level.
   *
   * This does not actually trigger a cache; to do this, call
   * [[EdgeRDD#cache]] on the returned EdgeRDD.
   */
  private[graphxp] def withTargetStorageLevel(targetStorageLevel: StorageLevel): EdgeRDD[ED]
}

object EdgeRDD {
  /**
   * Creates an EdgeRDD from a set of edges.
   *
   * @tparam ED the edge attribute type
   * @tparam VD the type of the vertex attributes that may be joined with the returned EdgeRDD
   */
  def fromEdges[ED: ClassTag, VD: ClassTag](edges: RDD[Edge[ED]]): impl.EdgeRDDImpl[ED, VD] = {
    val edgePartitions = edges.mapPartitionsWithIndex { (pid, iter) =>
      val builder = new EdgePartitionBuilder[ED, VD]
      iter.foreach { e =>
        builder.add(e.srcId, e.dstId, e.attr)
      }
      Iterator((pid, builder.toEdgePartition))
    }
    EdgeRDD.fromEdgePartitions(edgePartitions)
  }

  /**
   * Creates an EdgeRDD from already-constructed edge partitions.
   *
   * @tparam ED the edge attribute type
   * @tparam VD the type of the vertex attributes that may be joined with the returned EdgeRDD
   */
  private[graphxp] def fromEdgePartitions[ED: ClassTag, VD: ClassTag](
      edgePartitions: RDD[(Int, impl.EdgePartition[ED, VD])]): impl.EdgeRDDImpl[ED, VD] = {
    new impl.EdgeRDDImpl(edgePartitions)
  }
}