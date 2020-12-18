# S3 Bucket Keys

## S3 Access pattern

S3 buckets are used in 2 ways :

- bucket per user/entity
  
  Eg :
 
  user-bucket-1/
 
  user-bucket-2/
 
  user-bucket-3/


- Since there are only limited number of buckets available per account, some also follow user specific prefix within the same bucket

  bucket/user-1
 
  bucket/user-2
 
  bucket/user-3


So when you want to encrypt the data at rest, for approach 1, you would generally set encryption per bucket - i.e. set a kms key assigned to the user for the entire bucket. 
So that you do not need to pass the encryption configuration for each object upload.

And, for approach 2, you would upload each object with kms key for the user you are uploading for. 

## KMS limit - bottleneck when s3 is used as data lake

Amazon Athena allows you to query the data which is stored in S3. 
Depending on the table and partition you are querying for, Athena would load N objects from S3. 
And hitting KMS server to decrypt all these N files usually causing KMS limit exception when queries are executed from Athena.

There are 2 angles to this problem.
1. Why are there too many files? Have you partitioned the data efficiently?
1. Are there too many small files? Can you compact them to a bigger file thereby reducing the number of files loaded?

Say you have done both of the above and still, you just have so many files. Also, when you are executing various queries concurrently, you are bound to face this KMS limit issue.

## Introducing S3 bucket keys : reinvent 2020

AWS introduced bucket keys in S3. More details [here](https://docs.aws.amazon.com/AmazonS3/latest/dev/bucket-key.html). 
With this, the number of calls made from s3 to KMS server, to encrypt and decrypt the files, are reduced drastically. 

Since I was dealing with approach 2 of the access pattern mentioned above, I wasn't sure if it works at object level or only at bucket level. We were burning our money on this and really needed to make use of this feature sooner.

The documentation mentions how to enable this at bucket level. The "BucketKeyEnabled" is new here. 

```
aws s3api put-bucket-encryption --bucket <bucket-name> --server-side-encryption-configuration '
{"Rules" : [{"ApplyServerSideEncryptionByDefault" : {"SSEAlgorithm" : "aws:kms", "KMSMasterKeyID" : "arn:aws:kms:<region>:<acc>:key/<client-kms-id>"}, 
"BucketKeyEnabled" : true}]}'
```

It also mentions that you can pass the same boolean at PutObject API as well. 
But I was wondering if this is legit since there are millions of objects under the bucket - obviously since we are using a single bucket for all users and each user have thousands of records.
Would we be saving anything or end up facing any `bucketKey` limit at S3 side now? 

I asked [this](https://stackoverflow.com/questions/65291066/aws-s3-bucket-key-enabled) question and ended up relying on cloud trail to analyse the calls myself.

## Conclusion

Without bucket keys, to write and read 10K files, it took 10K calls to encrypt (KMS's GenerateDataKey API while writing to S3) and 10K calls to decrypt (KMS's Decrypt API while reading from S3)

With bucket keys, both at bucket level and object level for the same 10K files, it took about 10 for GenerateDataKey and 60 calls for Decrypt API. 

We also eventually heard back from AWS that there is no limit on number of kms keys per bucket, and the cost optimization should work regardless.

## Handy commands 

- Add encryption at bucket level
  ```
  aws s3api put-bucket-encryption --bucket <bucket-name> --server-side-encryption-configuration '
  {"Rules" : [{"ApplyServerSideEncryptionByDefault" : {"SSEAlgorithm" : "aws:kms", "KMSMasterKeyID" : "arn:aws:kms:<region>:<acc>:key/<client-kms-id>"}, 
  "BucketKeyEnabled" : true}]}'
  ```
- Remove bucket level encryption

  `aws s3api delete-bucket-encryption --bucket <bucket-name>`


- Creating cloud trail 

  `aws cloudtrail create-trail --name kmstrail --s3-bucket-name <trails-logs-bucket>`


- Determine what you want to trail. In this case, I want to trail the management calls made when certain bucket is used. 

  ```
  aws cloudtrail put-event-selectors --trail-name kmstrail --event-selectors '
  [
    {
      "ReadWriteType": "All",
      "IncludeManagementEvents": true,
      "DataResources": [
        {
           "Type":"AWS::S3::Object", 
           "Values": ["arn:aws:s3:::<bucket-name>/"]
        }
      ]
    }
  ]
  ```

- Start logging

  `aws cloudtrail start-logging --name kmstrail`


- Use the script [here](src/main/kotlin/com/foo/TestingS3BucketKeys.kt) to write and read objects from S3.


- Stop logging
  
  `aws cloudtrail stop-logging --name kmstrail`

- Look up events
 
  The start time and end time here are in UTC
   ```
   aws cloudtrail lookup-events --start-time "2020-12-15, 20:02" --end-time "2020-12-15, 20:04" --max-results 1000 --lookup-attributes 
   AttributeKey=Username,AttributeValue=vinodhinic | grep GenerateDataKey -B 20 | grep "<bucket-key>/<prefix>"
   ```
  This is how the events look like (heavily redacted)
  ```
  {
            "EventId": "redacted",
            "EventName": "Decrypt",
            "ReadOnly": "true",
            "AccessKeyId": "redacted",
            "EventTime": "2020-12-15T20:07:45+00:00",
            "EventSource": "kms.amazonaws.com",
            "Username": "redacted",
            "Resources": [],
            "CloudTrailEvent": "{"eventVersion":"1.05",
                   "userIdentity":{"type":"IAMUser","principalId":"redacted",
                               "arn":"arn:aws:iam::redacted:user/redacted",
                               "accountId":"redacted",
                               "accessKeyId":"redacted",
                               "userName":"redacted",
                               "sessionContext":{"sessionIssuer":{},"webIdFederationData":{},"attributes":{"mfaAuthenticated":"false","creationDate":"2020-12-15T20:07:40Z"}},
                               "invokedBy":"AWS Internal"},
                   "eventTime":"2020-12-15T20:07:45Z",
                   "eventSource":"kms.amazonaws.com",
                   "eventName":"Decrypt",
                   "awsRegion":"redacted",
                   "sourceIPAddress":"AWS Internal",
                   "userAgent":"AWS Internal",
                   "requestParameters":{
                     "encryptionAlgorithm":"SYMMETRIC_DEFAULT",
                     "encryptionContext":{"aws:s3:arn":"arn:aws:s3:::<bucket_name>/<prefix>/data-1"}
                   },
                   "responseElements":null,
                   "requestID":"redacted",
                   "eventID":"redacted",
                   "readOnly":true,
                   "resources":[{"accountId":"redacted","type":"AWS::KMS::Key","ARN":"arn:aws:kms:redacted"}],
                   "eventType":"AwsApiCall","recipientAccountId":"redacted"}"
  },
  ```
  
  You can also view the event history from the Cloud Trail dashboard. You can only apply on event filter. In my case, I went with username. You can also create a table on top of these trails and execute queries from Athena. 
  But for our use case, simple export to csv and excel sheet filters would do. 

- Note that there is about 15 mins delay in Cloud trail for the events to show up. i.e. if event time is 9:30 PM UTC, you would only see it at 9:45 PM UTC. Wait accordingly before firing the queries