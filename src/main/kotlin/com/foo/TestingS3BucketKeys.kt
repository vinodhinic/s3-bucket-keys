package com.foo

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.*
import com.google.common.collect.Streams
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.stream.Stream

private const val BUCKET_NAME = "BUCKET_NAME"
private const val KMS_KEY_ID = "KMS_KEY_ID"
private const val ACCESS_KEY = "ACCESS_KEY"
private const val SECRET_KEY = "SECRET_KEY"
private const val REGION = "REGION"

fun main() {
    val folder = "prefix"
    val bucketName = getSystemValue(BUCKET_NAME)
    val kmsKey = getSystemValue(KMS_KEY_ID)
    val accessKey = getSystemValue(ACCESS_KEY)
    val secretKey = getSystemValue(SECRET_KEY)
    val region = getSystemValue(REGION)
    val bucketKeyTest = BucketKeyTest(bucketName, kmsKey, accessKey, secretKey, region)
    bucketKeyTest.write(folder, true, false, 100, 10)
    bucketKeyTest.read(folder, 10)
}

private fun getSystemValue(key: String) = System.getenv(key) ?: System.getProperty(key)

data class Data(val id: Int)

class BucketKeyTest(
    val bucketName: String,
    val kmsKey: String,
    accessKey: String,
    secretKey: String,
    region: String
) {

    private val logger = LoggerFactory.getLogger(BucketKeyTest::class.java)
    private val gson = Gson()
    private val s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.fromName(region))
        .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey)))
        .build()

    private val executor = Executors.newFixedThreadPool(
        30,
        ThreadFactoryBuilder().setNameFormat("foo-executor").build()
    )

    internal class S3ObjectsIterator constructor(
        private var s3: AmazonS3,
        private var bucketName: String,
        private var key: String,
        resultsSize: Int
    ) : Iterator<S3ObjectSummary> {
        private var nextToken: String? = null
        private val bufferSize: Int = if (resultsSize < 1000) resultsSize else 1000
        private var objects: BlockingQueue<S3ObjectSummary> = ArrayBlockingQueue(bufferSize)

        init {
            refreshObjectListing()
        }

        private fun refreshObjectListing() {

            val listObjectsRequest = ListObjectsRequest()
                .withBucketName(bucketName)
                .withPrefix(key)
                .withMaxKeys(bufferSize)
                .withMarker(nextToken)
            val listObjectsResult = s3.listObjects(listObjectsRequest)
            objects.addAll(listObjectsResult.objectSummaries)

            nextToken = listObjectsResult.nextMarker
        }

        override fun hasNext(): Boolean {
            return objects.isNotEmpty() || nextToken != null
        }

        override fun next(): S3ObjectSummary {
            if (objects.isEmpty() && nextToken != null) {
                refreshObjectListing()
            }

            return objects.poll()
        }
    }

    fun read(prefix: String, breakAfter: Int? = null) {
        val records = mutableListOf<Data>()
        val resultsWanted = breakAfter ?: 1000
        for (summary in getObjects(prefix, resultsWanted)) {
            val getResponse = s3.getObject(bucketName, summary.key)
            logger.trace("is bucketKeyEnabled while reading? ${getResponse.objectMetadata.bucketKeyEnabled} kmsKey ${getResponse.objectMetadata.sseAwsKmsKeyId}")
            val byteArray = getResponse.objectContent.use { it.readAllBytes() }
            records.add(gson.fromJson(String(byteArray), Data::class.java))
            if (records.size % (resultsWanted / 4) == 0) { /* print when 1/4th of the results wanted are fetched */
                logger.info("Read ${records.size}")
            }

            if (breakAfter != null && records.size > breakAfter) {
                break
            }
        }
        logger.debug("$records")
        logger.info("Total Read :  ${records.size}")
    }

    private fun getObjects(prefix: String, resultsWanted: Int): Stream<S3ObjectSummary> {
        val iterator = S3ObjectsIterator(s3, bucketName, prefix, resultsWanted)
        return Streams.stream(iterator)
    }

    fun write(prefix: String, encrypt: Boolean, bucketKeyEnabled: Boolean, noOfRecords: Int, splitPerThread: Int) {
        val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
        runBlocking {
            (0..noOfRecords).step(splitPerThread).map {
                scope.launch {
                    val from = it
                    val to = it + splitPerThread
                    logger.info("Triggered async for id $from to $to")
                    for (id in from..to) {
                        val key = "$prefix/data-$id"
                        saveObject(bucketName, key, Data(id), encrypt, bucketKeyEnabled)
                    }
                    logger.info("Done with id $from to $to")
                }
            }.joinAll()
        }
        logger.info("Done writing")
        executor.shutdownNow()
    }

    private fun saveObject(bucketName: String, key: String, data: Data, encrypt: Boolean, bucketKeyEnabled: Boolean) {
        val toByteArray = gson.toJson(data).toByteArray()
        val inputStream = toByteArray.inputStream()
        val objectMetadata = ObjectMetadata()
        objectMetadata.contentLength = toByteArray.size.toLong()
        inputStream.use {
            val putObjectRequest: PutObjectRequest = if (encrypt) {
                PutObjectRequest(bucketName, key, inputStream, objectMetadata)
                    .withSSEAwsKeyManagementParams(SSEAwsKeyManagementParams(kmsKey))
                    .withBucketKeyEnabled(bucketKeyEnabled)

            } else {
                PutObjectRequest(bucketName, key, inputStream, objectMetadata)
            }

            val putObjectResult = s3.putObject(putObjectRequest)
            logger.trace("is bucketKeyEnabled ${putObjectResult.bucketKeyEnabled} kmsKey : ${putObjectResult.metadata.sseAwsKmsKeyId}")
        }
    }

}
