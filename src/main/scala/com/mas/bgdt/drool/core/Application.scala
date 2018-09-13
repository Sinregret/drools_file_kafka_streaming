package com.mas.bgdt.drool.core

import java.sql.Connection
import java.util.Date

import com.mas.bgdt.drool.config.Constants
import com.mas.bgdt.drool.handler.SQLiteHandler
import com.mas.bgdt.drool.kie.{KieSessionPool, RuleFileKie}
import com.mas.bgdt.drool.util.JSONUtil
import org.apache.spark.sql.SparkSession
import org.kie.api.runtime.KieSession

object Application {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("prj_sgm_iapps_platform")
//      .master("local[*]")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    val constants = new Constants(
      "jdbc:sqlite:/root/test.db",
      "192.168.117.117:9092",
      "test",
      "192.168.117.117:9092",
      "test_result")

    val inputstream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", constants.sourceBroker)
      .option("subscribe", constants.sourceTopic)
      .load()

    import spark.implicits._

    val query = inputstream
      .select($"value")
      .as[String]
      .mapPartitions(partition=>{
        //获取连接
        val conn: Connection = SQLiteHandler.getConnection(constants.jdbcUrl)
        partition.map(x=>{
        try{
          var lastRule:String =""
          val timestamp:Long = new Date().getTime
          val model = JSONUtil.JSON2Model(x)
          model.setTimestamp(timestamp)
          KieSessionPool.count +=1
          if(KieSessionPool.count % 200000 ==1) println(KieSessionPool.count +": "+ timestamp)
          while(model.getRuleGroup.startsWith("rule")&& !model.getRuleGroup.equals(lastRule)){
            lastRule = model.getRuleGroup
            model.setRuleFlow(model.getRuleFlow+model.getRuleGroup+";")
            val ks = KieSessionPool.getSession(conn,model.getRuleGroup,timestamp)
            val h = ks.insert(model)
            ks.fireAllRules()
            ks.delete(h) //不及时删除会OOM
          }
          JSONUtil.Model2JSON2(model)
//          model.toString
        }catch {
          case e:Exception => e.printStackTrace();""
        }
        })})
      .as[String]
      .writeStream
      .outputMode("append")
      .format("kafka")
      .option("checkpointLocation", "/opt/checkpoint")
      .option("kafka.bootstrap.servers", constants.targetBroker)
      .option("topic", constants.targetTopic)
      .start()

    query.awaitTermination()
  }
}
