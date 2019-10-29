package com.webank.eggroll

import com.webank.eggroll.format.{FrameBatch, FrameDB, FrameSchema}
import com.webank.eggroll.rollframe.{ClusterManager, RfPartition, RfStore, RollFrameService}

class RfServerLauncher{

}

object RfServerLauncher {
  /**
    * start server: scala -cp eggroll-rollframe.jar com.webank.eggroll.RfServerLauncher server 0 _
    * run client job:scala -cp eggroll-rollframe.jar com.webank.eggroll.RfServerLauncher client 0 map
    */

  private val clusterManager = new ClusterManager("cluster")
  def main(args: Array[String]): Unit = {
    printServerMes()
    val mode = args(0).toLowerCase() // server/client
    val nodeId = args(1) // 0,1,2
    val taskType = args(2).toLowerCase() // map,reduce,aggregate
    val clusterId = null

    // whether is't client mode
    mode match {
      case "client" => {
        println("Client Mode...")
        clientTask(taskType)
      }
      case _ => {
        println(s"Start RollFrame Servant, server id = $nodeId")
        clusterManager.startServerCluster(clusterId, nodeId)
      }
    }
  }

  def printServerMes(): Unit = {
    import java.net.InetAddress
    val localhost: InetAddress = InetAddress.getLocalHost
    val localIpAddress: String = localhost.getHostAddress
    val hostname = localhost.getHostName

    println(s"Host IP: $localIpAddress")
    println(s"Host Name: $hostname")
  }

  def clientTask(name: String): Unit = {
    name match {
      case "aggregate" => aggregateExample()
      case "map" => mapExample()
      case "reduce" => reduceExample()
      case _ => throw new UnsupportedOperationException("not such task...")
    }
  }

  def mapExample(): Unit = {
    val input = clusterManager.getRollFrameStore("b1", "test1") // 指定输入数据
    val output = RfStore("b1map", "test1", input.partitions.size, input.partitions) // 指定输出数据
    val rf = new RollFrameService(input)
    rf.mapBatches({ cb =>
      val schema =
        """
        {
          "fields": [
          {"name":"double1", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}},
          {"name":"double2", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}}
          ]
        }
      """.stripMargin
      val batch = new FrameBatch(new FrameSchema(schema), 2)
      batch.writeDouble(0, 0, 0.0 + cb.readDouble(0, 0))
      batch.writeDouble(0, 1, 0.1)
      batch.writeDouble(1, 0, 1.0)
      batch.writeDouble(1, 1, 1.1)
      batch
    }, output)

    new RollFrameService(output).mapBatches { fb =>
      assert(fb.readDouble(0, 1) == 0.1)
      assert(fb.readDouble(1, 0) == 1.0)
      assert(fb.readDouble(1, 1) == 1.1)
      fb
    }
  }

  def reduceExample(): Unit = {
    val start = System.currentTimeMillis()
    val input = clusterManager.getRollFrameStore("b1", "test1")
    val output = RfStore("b1reduce", "test1", 1, List(RfPartition(0, 1)))
    val rf = new RollFrameService(input)
    rf.reduce ({(x, y) =>
      try {
        for (f <- y.rootVectors.indices) {
          var sum = 0.0
          val fv = y.rootVectors(f)
          for (i <- 0 until fv.valueCount) {
            sum += fv.readDouble(i)
          }
          x.writeDouble(f, 0, sum)
        }
      } catch {
        case t: Throwable => t.printStackTrace()
      }
      x},output)

    println(s"reduce time: ${System.currentTimeMillis() - start}")

  }

  def aggregateExample(): Unit = {
    val start = System.currentTimeMillis()
    val rf = new RollFrameService(clusterManager.getRollFrameStore("b1", "test1"))
    println(System.currentTimeMillis() - start)

    val fieldCount = 100
    val schema = getSchema(fieldCount)
    val zeroValue = new FrameBatch(new FrameSchema(schema), 1)
    (0 until fieldCount).foreach(i => zeroValue.writeDouble(i, 0, 0))

    rf.aggregate(zeroValue, { (x, y) =>
      try {
        for (f <- y.rootVectors.indices) {
          var sum = 0.0
          val fv = y.rootVectors(f)
          for (i <- 0 until fv.valueCount) {
            //            sum += fv.readDouble(i)
            sum += 1
          }
          x.writeDouble(f, 0, sum)
          //          x.writeDouble(f,0, fv.valueCount)
        }
      } catch {
        case t: Throwable => t.printStackTrace()
      }
      //      x.columnarVectors.foreach(_.valueCount(5))
      println("x.sum(1) = ", x.readDouble(0, 0))
      println("x,sum(2) = ", x.readDouble(1, 0))
      x
    }, { (a, b) =>
      for (i <- 0 until fieldCount) {
        a.writeDouble(i, 0, a.readDouble(i, 0) + b.readDouble(i, 0))
      }
      a
    })
    println(s"total time: ${System.currentTimeMillis() - start}")
  }

  private def getSchema(fieldCount: Int): String = {

    val sb = new StringBuilder
    sb.append("""{"fields": [""")
    (0 until fieldCount).foreach { i =>
      if (i > 0) {
        sb.append(",")
      }
      sb.append(s"""{"name":"double$i", "type": {"name" : "floatingpoint","precision" : "DOUBLE"}}""")
    }
    sb.append("]}")
    sb.toString()
  }
}
