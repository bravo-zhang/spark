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

package org.apache.spark.sql.execution.command

import java.net.URI

import scala.util.control.NonFatal

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogStatistics, CatalogTable}
import org.apache.spark.sql.internal.SessionState


object CommandUtils extends Logging {

  /** Change statistics after changing data by commands. */
  def updateTableStats(sparkSession: SparkSession, table: CatalogTable): Unit = {
    if (table.stats.nonEmpty) {
      val catalog = sparkSession.sessionState.catalog
      if (sparkSession.sessionState.conf.autoUpdateSize) {
        val newTable = catalog.getTableMetadata(table.identifier)
        val newSize = CommandUtils.calculateTotalSize(sparkSession.sessionState, newTable)
        val newStats = CatalogStatistics(sizeInBytes = newSize)
        catalog.alterTableStats(table.identifier, Some(newStats))
      } else {
        catalog.alterTableStats(table.identifier, None)
      }
    }
  }

  def calculateTotalSize(sessionState: SessionState, catalogTable: CatalogTable): BigInt = {
    if (catalogTable.partitionColumnNames.isEmpty) {
      calculateLocationSize(sessionState, catalogTable.identifier, catalogTable.storage.locationUri)
    } else {
      // Calculate table size as a sum of the visible partitions. See SPARK-21079
      val partitions = sessionState.catalog.listPartitions(catalogTable.identifier)
      partitions.map { p =>
        calculateLocationSize(sessionState, catalogTable.identifier, p.storage.locationUri)
      }.sum
    }
  }

  def calculateLocationSize(
      sessionState: SessionState,
      identifier: TableIdentifier,
      locationUri: Option[URI]): Long = {
    // This method is mainly based on
    // org.apache.hadoop.hive.ql.stats.StatsUtils.getFileSizeForTable(HiveConf, Table)
    // in Hive 0.13 (except that we do not use fs.getContentSummary).
    // TODO: Generalize statistics collection.
    // TODO: Why fs.getContentSummary returns wrong size on Jenkins?
    // Can we use fs.getContentSummary in future?
    // Seems fs.getContentSummary returns wrong table size on Jenkins. So we use
    // countFileSize to count the table size.
    val stagingDir = sessionState.conf.getConfString("hive.exec.stagingdir", ".hive-staging")

    def getPathSize(fs: FileSystem, path: Path): Long = {
      val fileStatus = fs.getFileStatus(path)
      val size = if (fileStatus.isDirectory) {
        fs.listStatus(path)
          .map { status =>
            if (!status.getPath.getName.startsWith(stagingDir)) {
              getPathSize(fs, status.getPath)
            } else {
              0L
            }
          }.sum
      } else {
        fileStatus.getLen
      }

      size
    }

    val startTime = System.nanoTime()
    logInfo(s"Starting to calculate the total file size under path $locationUri.")
    val size = locationUri.map { p =>
      val path = new Path(p)
      try {
        val fs = path.getFileSystem(sessionState.newHadoopConf())
        getPathSize(fs, path)
      } catch {
        case NonFatal(e) =>
          logWarning(
            s"Failed to get the size of table ${identifier.table} in the " +
              s"database ${identifier.database} because of ${e.toString}", e)
          0L
      }
    }.getOrElse(0L)
    val durationInMs = (System.nanoTime() - startTime) / (1000 * 1000)
    logInfo(s"It took $durationInMs ms to calculate the total file size under path $locationUri.")

    size
  }

}
