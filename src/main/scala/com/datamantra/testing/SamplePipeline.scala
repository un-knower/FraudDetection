package com.datamantra.testing

import com.datamantra.jobs.SparkJob
import com.datamantra.pipeline.{FeatureExtraction, BuildPipeline}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.{Transformer, Estimator, Pipeline}
import org.apache.spark.ml.clustering.{KMeansModel, KMeans}
import org.apache.spark.ml.feature.{StringIndexer, OneHotEncoder, VectorAssembler}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType, StringType, IntegerType}
import org.apache.spark.sql.{Row, SparkSession, DataFrame}
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType

/**
 * Created by kafka on 11/5/18.
 */
object SamplePipeline extends SparkJob("Sample App"){

  def getDistance (lat1:Double, lon1:Double, lat2:Double, lon2:Double) = {
    val r : Int = 6371 //Earth radius
    val latDistance : Double = Math.toRadians(lat2 - lat1)
    val lonDistance : Double = Math.toRadians(lon2 - lon1)
    val a : Double = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
    val c : Double = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val distance : Double = r * c
    distance
  }

  def main(args: Array[String]) {


    import sparkSession.implicits._
    val readOption = Map("inferSchema" -> "true", "header" -> "true")

    val rawTransactionDF = sparkSession.read
      .options(readOption)
      .csv(conf.rawTransactionDataSource)
    rawTransactionDF.printSchema()


    val rawCustomerDF = sparkSession.read
      .options(readOption)
      .csv(conf.rawCustomerDataSource)
    rawCustomerDF.printSchema()


    val customer_age_df = rawCustomerDF
      .withColumn("age", (datediff(current_date(),to_date($"dob"))/365).cast(IntegerType))
      .withColumnRenamed("cc_num", "cardNo")


    val distance_udf = udf(getDistance _)

    val processedTransactionDF = customer_age_df.join(rawTransactionDF, customer_age_df("cardNo") === rawTransactionDF("cc_num"))
      .withColumn("distance", lit(round(distance_udf($"lat", $"long", $"merch_lat", $"merch_long"), 2)))
      .selectExpr("cast(cc_num as string) cc_num" , "category", "merchant", "distance", "amt", "age", "is_fraud")

    processedTransactionDF.cache()

    val fraudDF = processedTransactionDF.filter($"is_fraud" === 1)
    val nonFraudDF = processedTransactionDF.filter($"is_fraud" === 0)

    val fraudCount = fraudDF.count()


    val ccIndexer = new StringIndexer().setInputCol("cc_num").setOutputCol("cc_numIndex")
    val categoryIndexer = new StringIndexer().setInputCol("category").setOutputCol("categoryIndex")
    val merchantIndexer = new StringIndexer().setInputCol("merchant").setOutputCol("merchantIndex")
    val allIndexer = Array(ccIndexer, categoryIndexer, merchantIndexer)
    val featurecoloumns = Array("cc_numIndex", "categoryIndex", "merchantIndex", "distance", "amt", "age")
    val vectorAssembler = new VectorAssembler().setInputCols(featurecoloumns).setOutputCol("features")

    val pipeline = new Pipeline().setStages(allIndexer :+ vectorAssembler)
    val dummyModel = pipeline.fit(nonFraudDF)
    val feautureNonFraudDF = dummyModel.transform(nonFraudDF)

    feautureNonFraudDF.show(false)

    val kMeans = new KMeans().setK(fraudCount.toInt).setMaxIter(30)
    val kMeansPredictionModel = kMeans.fit(feautureNonFraudDF)

    val featureSchema = StructType(Array(StructField("feature_1", VectorType, true)))
    val rowList = kMeansPredictionModel.clusterCenters.toList.map(v => Row(v))
    val rowRdd = sparkSession.sparkContext.makeRDD(rowList)
    val sampledFeatureDF = sparkSession.createDataFrame(rowRdd, featureSchema)
    sampledFeatureDF.show(false)

  }
}
