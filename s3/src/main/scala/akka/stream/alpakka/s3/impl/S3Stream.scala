/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.s3.impl

import java.net.InetSocketAddress
import java.time.{Instant, ZoneOffset, ZonedDateTime}

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.{Failure, Success}
import akka.{Done, NotUsed}
import akka.http.scaladsl.{ClientTransport, Http}
import akka.http.scaladsl.model.StatusCodes.{NoContent, NotFound, OK}
import akka.http.scaladsl.model.headers.{
  `Content-Length`,
  `Content-Type`,
  BasicHttpCredentials,
  ByteRange,
  CustomHeader
}
import akka.http.scaladsl.model.{headers => http, _}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.alpakka.s3.BucketAccess.{AccessDenied, AccessGranted, NotExists}
import akka.stream.{ActorMaterializer, Attributes, Materializer}
import akka.stream.alpakka.s3.impl.auth.{CredentialScope, Signer, SigningKey}
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.headers.ServerSideEncryption
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import akka.util.ByteString

/** Internal Api */
@InternalApi private[s3] final case class S3Location(bucket: String, key: String)

/** Internal Api */
@InternalApi private[impl] final case class MultipartUpload(s3Location: S3Location, uploadId: String)

/** Internal Api */
@InternalApi private[impl] sealed trait UploadPartResponse {
  def multipartUpload: MultipartUpload

  def index: Int
}

/** Internal Api */
@InternalApi private[impl] final case class SuccessfulUploadPart(multipartUpload: MultipartUpload,
                                                                 index: Int,
                                                                 etag: String)
    extends UploadPartResponse

/** Internal Api */
@InternalApi private[impl] final case class FailedUploadPart(multipartUpload: MultipartUpload,
                                                             index: Int,
                                                             exception: Throwable)
    extends UploadPartResponse

/** Internal Api */
@InternalApi private[impl] final case class CompleteMultipartUploadResult(location: Uri,
                                                                          bucket: String,
                                                                          key: String,
                                                                          etag: String,
                                                                          versionId: Option[String] = None)

/** Internal Api */
@InternalApi private[impl] final case class ListBucketResult(isTruncated: Boolean,
                                                             continuationToken: Option[String],
                                                             contents: Seq[ListBucketResultContents])

/** Internal Api */
@InternalApi private[impl] final case class CopyPartResult(lastModified: Instant, eTag: String)

/** Internal Api */
@InternalApi private[impl] final case class CopyPartition(partNumber: Int,
                                                          sourceLocation: S3Location,
                                                          range: Option[ByteRange.Slice] = None)

/** Internal Api */
@InternalApi private[impl] final case class MultipartCopy(multipartUpload: MultipartUpload,
                                                          copyPartition: CopyPartition)

/** Internal Api */
@InternalApi private[s3] object S3Stream {

  import HttpRequests._
  import Marshalling._

  val MinChunkSize: Int = 5 * 1024 * 1024 //in bytes
  val MaxChunkSize: Int = 10 * 1024 * 1024 //in bytes

  // def because tokens can expire
  def signingKey(implicit settings: S3Settings) = {
    val requestDate = ZonedDateTime.now(ZoneOffset.UTC)
    SigningKey(
      requestDate,
      settings.credentialsProvider,
      CredentialScope(requestDate.toLocalDate, settings.s3RegionProvider.getRegion, "s3")
    )
  }

  def download(
      s3Location: S3Location,
      range: Option[ByteRange],
      versionId: Option[String],
      s3Headers: S3Headers
  ): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] = {
    val headers = s3Headers.headersFor(GetObject)

    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        request(s3Location, rangeOption = range, versionId = versionId, s3Headers = headers)
          .map(response => response.withEntity(response.entity.withoutSizeLimit))
          .mapAsync(parallelism = 1)(entityForSuccess)
          .map {
            case (entity, headers) =>
              Option((entity.dataBytes.mapMaterializedValue(_ => NotUsed), computeMetaData(headers, entity)))
          }
          .recover[Option[(Source[ByteString, NotUsed], ObjectMetadata)]] {
            case e: S3Exception if e.code == "NoSuchKey" => None
          }
      }
  }.mapMaterializedValue(_ => NotUsed)

  def listBucket(bucket: String,
                 prefix: Option[String] = None,
                 s3Headers: S3Headers): Source[ListBucketResultContents, NotUsed] = {
    sealed trait ListBucketState
    case object Starting extends ListBucketState
    case class Running(continuationToken: String) extends ListBucketState
    case object Finished extends ListBucketState

    def listBucketCall(
        token: Option[String]
    )(implicit mat: ActorMaterializer,
      attr: Attributes): Future[Option[(ListBucketState, Seq[ListBucketResultContents])]] = {
      import mat.executionContext
      implicit val conf = resolveSettings(attr, mat.system)

      signAndGetAs[ListBucketResult](HttpRequests.listBucket(bucket, prefix, token, s3Headers.headersFor(ListBucket)))
        .map { (res: ListBucketResult) =>
          Some(
            res.continuationToken
              .fold[(ListBucketState, Seq[ListBucketResultContents])]((Finished, res.contents))(
                t => (Running(t), res.contents)
              )
          )
        }
    }

    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        implicit val attributes = attr
        Source
          .unfoldAsync[ListBucketState, Seq[ListBucketResultContents]](Starting) {
            case Finished => Future.successful(None)
            case Starting => listBucketCall(None)
            case Running(token) => listBucketCall(Some(token))
          }
          .mapConcat(identity)
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  def getObjectMetadata(bucket: String,
                        key: String,
                        versionId: Option[String],
                        s3Headers: S3Headers): Source[Option[ObjectMetadata], NotUsed] =
    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        import mat.executionContext
        val headers = s3Headers.headersFor(HeadObject)
        request(S3Location(bucket, key), HttpMethods.HEAD, versionId = versionId, s3Headers = headers)
          .flatMapConcat {
            case HttpResponse(OK, headers, entity, _) =>
              Source.fromFuture {
                entity.withoutSizeLimit().discardBytes().future().map { _ =>
                  Some(computeMetaData(headers, entity))
                }
              }
            case HttpResponse(NotFound, _, entity, _) =>
              Source.fromFuture(entity.discardBytes().future().map(_ => None))
            case HttpResponse(code, _, entity, _) =>
              Source.fromFuture {
                Unmarshal(entity).to[String].map { err =>
                  throw new S3Exception(err, code)
                }
              }
          }
      }
      .mapMaterializedValue(_ => NotUsed)

  def deleteObject(s3Location: S3Location, versionId: Option[String], s3Headers: S3Headers): Source[Done, NotUsed] =
    Source
      .setup { (mat, attr) =>
        implicit val m = mat
        import mat.executionContext

        val headers = s3Headers.headersFor(DeleteObject)
        request(s3Location, HttpMethods.DELETE, versionId = versionId, s3Headers = headers).flatMapConcat {
          case HttpResponse(NoContent, _, entity, _) =>
            Source.fromFuture(entity.discardBytes().future().map(_ => Done))
          case HttpResponse(code, _, entity, _) =>
            Source.fromFuture {
              Unmarshal(entity).to[String].map { err =>
                throw new S3Exception(err, code)
              }
            }
        }
      }
      .mapMaterializedValue(_ => NotUsed)

  def deleteObjectsByPrefix(bucket: String, prefix: Option[String], s3Headers: S3Headers): Source[Done, NotUsed] =
    listBucket(bucket, prefix, s3Headers)
      .flatMapConcat(
        listBucketResultContents =>
          deleteObject(S3Location(bucket, listBucketResultContents.key), versionId = None, s3Headers)
      )

  def putObject(s3Location: S3Location,
                contentType: ContentType,
                data: Source[ByteString, _],
                contentLength: Long,
                s3Headers: S3Headers): Source[ObjectMetadata, NotUsed] = {

    // TODO can we take in a Source[ByteString, NotUsed] without forcing chunking
    // chunked requests are causing S3 to think this is a multipart upload

    val headers = s3Headers.headersFor(PutObject)

    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        implicit val attributes = attr
        import mat.executionContext
        implicit val sys = mat.system
        implicit val conf = resolveSettings(attr, mat.system)

        val req = uploadRequest(s3Location, data, contentLength, contentType, headers)

        signAndRequest(req)
          .flatMapConcat {
            case HttpResponse(OK, h, entity, _) =>
              Source.fromFuture {
                entity.discardBytes().future().map { _ =>
                  ObjectMetadata(h :+ `Content-Length`(entity.contentLengthOption.getOrElse(0)))
                }
              }
            case HttpResponse(code, _, entity, _) =>
              Source.fromFuture {
                Unmarshal(entity).to[String].map { err =>
                  throw new S3Exception(err, code)
                }
              }
          }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  def request(s3Location: S3Location,
              method: HttpMethod = HttpMethods.GET,
              rangeOption: Option[ByteRange] = None,
              versionId: Option[String] = None,
              s3Headers: Seq[HttpHeader] = Seq.empty): Source[HttpResponse, NotUsed] =
    Source
      .setup { (mat, attr) =>
        implicit val attributes = attr
        implicit val sys = mat.system
        implicit val conf = resolveSettings(attr, mat.system)
        signAndRequest(requestHeaders(getDownloadRequest(s3Location, method, s3Headers, versionId), rangeOption))
      }
      .mapMaterializedValue(_ => NotUsed)

  private def requestHeaders(downloadRequest: HttpRequest, rangeOption: Option[ByteRange]): HttpRequest =
    rangeOption match {
      case Some(range) => downloadRequest.addHeader(http.Range(range))
      case _ => downloadRequest
    }

  def makeBucketSource(bucket: String, headers: S3Headers): Source[Done, NotUsed] =
    bucketManagementRequest[Done](
      bucket = bucket,
      method = HttpMethods.PUT,
      headers.headersFor(MakeBucket),
      process = processBucketLifecycleResponse
    )

  def makeBucket(bucket: String, headers: S3Headers)(implicit mat: Materializer, attr: Attributes): Future[Done] =
    makeBucketSource(bucket, headers).withAttributes(attr).runWith(Sink.ignore)

  def deleteBucketSource(bucket: String, headers: S3Headers): Source[Done, NotUsed] =
    bucketManagementRequest[Done](
      bucket = bucket,
      method = HttpMethods.DELETE,
      headers.headersFor(DeleteBucket),
      process = processBucketLifecycleResponse
    )

  def deleteBucket(bucket: String, headers: S3Headers)(implicit mat: Materializer, attr: Attributes): Future[Done] =
    deleteBucketSource(bucket, headers).withAttributes(attr).runWith(Sink.ignore)

  def checkIfBucketExistsSource(bucketName: String, headers: S3Headers): Source[BucketAccess, NotUsed] =
    bucketManagementRequest[BucketAccess](
      bucket = bucketName,
      method = HttpMethods.HEAD,
      headers.headersFor(CheckBucket),
      process = processCheckIfExistsResponse
    )

  def checkIfBucketExists(bucket: String, headers: S3Headers)(implicit mat: Materializer,
                                                              attr: Attributes): Future[BucketAccess] =
    checkIfBucketExistsSource(bucket, headers).withAttributes(attr).runWith(Sink.head)

  private def bucketManagementRequest[T](
      bucket: String,
      method: HttpMethod,
      headers: Seq[HttpHeader],
      process: (HttpResponse, Materializer) => Future[T]
  ): Source[T, NotUsed] =
    Source
      .setup { (mat, attr) =>
        implicit val attributes = attr
        implicit val sys: ActorSystem = mat.system
        implicit val conf: S3Settings = resolveSettings(attr, mat.system)

        val location = S3Location(bucket = bucket, key = "")

        signAndRequest(
          requestHeaders(
            HttpRequests.bucketManagementRequest(location, method),
            None
          )
        ).mapAsync(1) { response =>
          process(response, mat)
        }
      }
      .mapMaterializedValue(_ => NotUsed)

  private def processBucketLifecycleResponse(response: HttpResponse, materializer: Materializer): Future[Done] = {
    import materializer.executionContext

    implicit val mat: Materializer = materializer

    response match {
      case HttpResponse(status, _, entity, _) if status.isSuccess() =>
        entity.discardBytes().future()
      case HttpResponse(code, _, entity, _) =>
        Unmarshal(entity).to[String].map { err =>
          throw new S3Exception(err, code)
        }
    }
  }

  private def processCheckIfExistsResponse(response: HttpResponse, materializer: Materializer): Future[BucketAccess] = {
    import materializer.executionContext

    implicit val mat: Materializer = materializer

    response match {
      case code @ HttpResponse(StatusCodes.NotFound | StatusCodes.Forbidden | StatusCodes.OK, _, entity, _) =>
        entity
          .discardBytes()
          .future()
          .map(
            _ =>
              code.status match {
                case StatusCodes.NotFound => NotExists
                case StatusCodes.Forbidden => AccessDenied
                case StatusCodes.OK => AccessGranted
                case other => throw new IllegalArgumentException(s"received status $other")
              }
          )
      case HttpResponse(code, _, entity, _) =>
        Unmarshal(entity).to[String].map { err =>
          throw new S3Exception(err, code)
        }
    }
  }

  /**
   * Uploads a stream of ByteStrings to a specified location as a multipart upload.
   */
  def multipartUpload(
      s3Location: S3Location,
      contentType: ContentType = ContentTypes.`application/octet-stream`,
      s3Headers: S3Headers,
      chunkSize: Int = MinChunkSize,
      chunkingParallelism: Int = 4
  ): Sink[ByteString, Future[MultipartUploadResult]] =
    chunkAndRequest(s3Location, contentType, s3Headers, chunkSize)(chunkingParallelism)
      .toMat(completionSink(s3Location, s3Headers.serverSideEncryption))(Keep.right)

  private def initiateMultipartUpload(s3Location: S3Location,
                                      contentType: ContentType,
                                      s3Headers: Seq[HttpHeader]): Source[MultipartUpload, NotUsed] =
    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        implicit val attributes = attr
        import mat.executionContext
        implicit val sys = mat.system
        implicit val conf = resolveSettings(attr, mat.system)

        val req = initiateMultipartUploadRequest(s3Location, contentType, s3Headers)

        signAndRequest(req).flatMapConcat {
          case HttpResponse(status, _, entity, _) if status.isSuccess() =>
            Source.fromFuture(Unmarshal(entity).to[MultipartUpload])
          case HttpResponse(code, _, entity, _) =>
            Source.fromFuture {
              Unmarshal(entity).to[String].map { err =>
                throw new S3Exception(err, code)
              }
            }
        }
      }
      .mapMaterializedValue(_ => NotUsed)

  def multipartCopy(
      sourceLocation: S3Location,
      targetLocation: S3Location,
      sourceVersionId: Option[String] = None,
      contentType: ContentType = ContentTypes.`application/octet-stream`,
      s3Headers: S3Headers,
      chunkSize: Int = MinChunkSize,
      chunkingParallelism: Int = 4
  ): RunnableGraph[Future[MultipartUploadResult]] = {

    // Pre step get source meta to get content length (size of the object)
    val eventualMaybeObjectSize =
      getObjectMetadata(sourceLocation.bucket, sourceLocation.key, sourceVersionId, s3Headers)
        .map(_.map(_.contentLength))
    val eventualPartitions =
      eventualMaybeObjectSize.map(_.map(createPartitions(chunkSize, sourceLocation)).getOrElse(Nil))

    // Multipart copy upload requests (except for the completion api) are created here.
    //  The initial copy upload request gets executed within this function as well.
    //  The individual copy upload part requests are created.
    val copyRequests =
      createCopyRequests(targetLocation, sourceVersionId, contentType, s3Headers, eventualPartitions)(
        chunkingParallelism
      )

    // The individual copy upload part requests are processed here
    processUploadCopyPartRequests(copyRequests)(chunkingParallelism)
      .toMat(completionSink(targetLocation, s3Headers.serverSideEncryption))(Keep.right)
  }

  private def computeMetaData(headers: Seq[HttpHeader], entity: ResponseEntity): ObjectMetadata =
    ObjectMetadata(
      headers ++
      Seq(
        `Content-Length`(entity.contentLengthOption.getOrElse(0)),
        `Content-Type`(entity.contentType),
        CustomContentTypeHeader(entity.contentType)
      )
    )

  //`Content-Type` header is by design not accessible as header. So need to have a custom
  //header implementation to expose that
  private case class CustomContentTypeHeader(contentType: ContentType) extends CustomHeader {
    override def name(): String = "Content-Type"
    override def value(): String = contentType.value
    override def renderInRequests(): Boolean = true
    override def renderInResponses(): Boolean = true
  }

  private def completeMultipartUpload(s3Location: S3Location,
                                      parts: Seq[SuccessfulUploadPart],
                                      sse: Option[ServerSideEncryption])(
      implicit mat: ActorMaterializer,
      attr: Attributes
  ): Future[CompleteMultipartUploadResult] = {
    def populateResult(result: CompleteMultipartUploadResult,
                       headers: Seq[HttpHeader]): CompleteMultipartUploadResult = {
      val versionId = headers.find(_.lowercaseName() == "x-amz-version-id").map(_.value())
      result.copy(versionId = versionId)
    }

    import mat.executionContext
    implicit val conf = resolveSettings(attr, mat.system)

    val headers = sse.toIndexedSeq.flatMap(_.headersFor(UploadPart))

    Source
      .fromFuture(
        completeMultipartUploadRequest(parts.head.multipartUpload, parts.map(p => p.index -> p.etag), headers)
      )
      .flatMapConcat(signAndGetAs[CompleteMultipartUploadResult](_, populateResult(_, _)))
      .runWith(Sink.head)
  }

  /**
   * Initiates a multipart upload. Returns a source of the initiated upload with upload part indicess
   */
  private def initiateUpload(s3Location: S3Location,
                             contentType: ContentType,
                             s3Headers: Seq[HttpHeader]): Source[(MultipartUpload, Int), NotUsed] =
    Source
      .single(s3Location)
      .flatMapConcat(initiateMultipartUpload(_, contentType, s3Headers))
      .mapConcat(r => Stream.continually(r))
      .zip(Source.fromIterator(() => Iterator.from(1)))

  val atLeastOneByteString = Flow[ByteString].orElse(Source.single(ByteString.empty))

  private def createRequests(
      s3Location: S3Location,
      contentType: ContentType,
      s3Headers: S3Headers,
      chunkSize: Int,
      parallelism: Int
  ): Flow[ByteString, (HttpRequest, (MultipartUpload, Int)), NotUsed] = {

    assert(
      chunkSize >= MinChunkSize,
      s"Chunk size must be at least 5 MB = $MinChunkSize bytes (was $chunkSize bytes). See http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadUploadPart.html"
    )

    // First step of the multi part upload process is made.
    //  The response is then used to construct the subsequent individual upload part requests
    val requestInfo: Source[(MultipartUpload, Int), NotUsed] =
      initiateUpload(s3Location, contentType, s3Headers.headersFor(InitiateMultipartUpload))

    val headers = s3Headers.serverSideEncryption.toIndexedSeq.flatMap(_.headersFor(UploadPart))

    Flow
      .setup { (mat, attr) =>
        implicit val conf = resolveSettings(attr, mat.system)

        SplitAfterSize(chunkSize, MaxChunkSize)(atLeastOneByteString)
          .via(getChunkBuffer(chunkSize)) //creates the chunks
          .concatSubstreams
          .zipWith(requestInfo) {
            case (chunkedPayload, (uploadInfo, chunkIndex)) =>
              //each of the payload requests are created
              val partRequest =
                uploadPartRequest(uploadInfo, chunkIndex, chunkedPayload.data, chunkedPayload.size, headers)
              (partRequest, (uploadInfo, chunkIndex))
          }
          .flatMapConcat { case (req, info) => Signer.signedRequest(req, signingKey).zip(Source.single(info)) }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  private def getChunkBuffer(chunkSize: Int)(implicit settings: S3Settings) = settings.bufferType match {
    case MemoryBufferType =>
      new MemoryBuffer(chunkSize * 2)
    case d: DiskBufferType =>
      new DiskBuffer(2, chunkSize * 2, d.path)
  }

  private def poolSettings(implicit settings: S3Settings, system: ActorSystem) =
    settings.forwardProxy.map(proxy => {
      val address = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
      val transport = proxy.credentials.fold(ClientTransport.httpsProxy(address))(
        c => ClientTransport.httpsProxy(address, BasicHttpCredentials(c.username, c.password))
      )

      ConnectionPoolSettings(system)
        .withConnectionSettings(
          ClientConnectionSettings(system)
            .withTransport(transport)
        )
    })

  private def singleRequest(req: HttpRequest)(implicit settings: S3Settings, system: ActorSystem) =
    poolSettings.fold(Http().singleRequest(req))(s => Http().singleRequest(req, settings = s))

  private def superPool[T](implicit settings: S3Settings, sys: ActorSystem) =
    poolSettings.fold(Http().superPool[T]())(s => Http().superPool[T](settings = s))

  private def chunkAndRequest(
      s3Location: S3Location,
      contentType: ContentType,
      s3Headers: S3Headers,
      chunkSize: Int
  )(parallelism: Int): Flow[ByteString, UploadPartResponse, NotUsed] = {

    // Multipart upload requests (except for the completion api) are created here.
    //  The initial upload request gets executed within this function as well.
    //  The individual upload part requests are created.
    val requestFlow = createRequests(s3Location, contentType, s3Headers, chunkSize, parallelism)

    // The individual upload part requests are processed here
    Flow
      .setup { (mat, attr) =>
        implicit val materializer = mat
        import mat.executionContext
        implicit val sys = mat.system
        implicit val conf = resolveSettings(attr, mat.system)

        requestFlow
          .via(superPool[(MultipartUpload, Int)])
          .mapAsync(parallelism) {
            case (Success(r), (upload, index)) =>
              if (r.status.isFailure()) {
                Unmarshal(r.entity).to[String].map { errorBody =>
                  FailedUploadPart(
                    upload,
                    index,
                    new RuntimeException(
                      s"Upload part $index request failed. Response header: ($r), response body: ($errorBody)."
                    )
                  )
                }
              } else {
                r.entity.dataBytes.runWith(Sink.ignore)
                val etag = r.headers.find(_.lowercaseName() == "etag").map(_.value)
                etag
                  .map(t => Future.successful(SuccessfulUploadPart(upload, index, t)))
                  .getOrElse(
                    Future
                      .successful(FailedUploadPart(upload, index, new RuntimeException(s"Cannot find etag in ${r}")))
                  )
              }

            case (Failure(e), (upload, index)) => Future.successful(FailedUploadPart(upload, index, e))
          }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  private def completionSink(
      s3Location: S3Location,
      sse: Option[ServerSideEncryption]
  ): Sink[UploadPartResponse, Future[MultipartUploadResult]] =
    Sink
      .setup { (mat, attr) =>
        implicit val materializer = mat
        implicit val attributes = attr
        val sys = mat.system
        import sys.dispatcher
        Sink
          .seq[UploadPartResponse]
          .mapMaterializedValue { responseFuture: Future[Seq[UploadPartResponse]] =>
            responseFuture
              .flatMap { responses: Seq[UploadPartResponse] =>
                val successes = responses.collect { case r: SuccessfulUploadPart => r }
                val failures = responses.collect { case r: FailedUploadPart => r }
                if (responses.isEmpty) {
                  Future.failed(new RuntimeException("No Responses"))
                } else if (failures.isEmpty) {
                  Future.successful(successes.sortBy(_.index))
                } else {
                  Future.failed(FailedUpload(failures.map(_.exception)))
                }
              }
              .flatMap(completeMultipartUpload(s3Location, _, sse))
          }
          .mapMaterializedValue(_.map(r => MultipartUploadResult(r.location, r.bucket, r.key, r.etag, r.versionId)))
      }
      .mapMaterializedValue(_.flatMap(identity)(ExecutionContexts.sameThreadExecutionContext))

  private def signAndGetAs[T](
      request: HttpRequest
  )(implicit um: Unmarshaller[ResponseEntity, T], mat: ActorMaterializer, attr: Attributes): Future[T] = {
    import mat.executionContext
    implicit val sys = mat.system
    for {
      response <- signAndRequest(request).runWith(Sink.head)
      (entity, _) <- entityForSuccess(response)
      t <- Unmarshal(entity).to[T]
    } yield t
  }

  private def signAndGetAs[T](
      request: HttpRequest,
      f: (T, Seq[HttpHeader]) => T
  )(implicit um: Unmarshaller[ResponseEntity, T], mat: ActorMaterializer, attr: Attributes): Source[T, NotUsed] = {
    import mat.executionContext
    implicit val sys = mat.system
    signAndRequest(request)
      .mapAsync(parallelism = 1)(entityForSuccess)
      .mapAsync(parallelism = 1) {
        case (entity, headers) => Unmarshal(entity).to[T].map((_, headers))
      }
      .map(f.tupled)
  }

  private def signAndRequest(
      request: HttpRequest,
      retries: Int = 3
  )(implicit sys: ActorSystem, attr: Attributes): Source[HttpResponse, NotUsed] = {
    implicit val conf = resolveSettings(attr, sys)

    Signer
      .signedRequest(request, signingKey)
      .mapAsync(parallelism = 1)(req => singleRequest(req))
      .flatMapConcat {
        case HttpResponse(status, _, _, _) if (retries > 0) && (500 to 599 contains status.intValue()) =>
          signAndRequest(request, retries - 1)
        case res => Source.single(res)
      }
  }

  private def entityForSuccess(
      resp: HttpResponse
  )(implicit mat: Materializer): Future[(ResponseEntity, Seq[HttpHeader])] = {
    import mat.executionContext
    resp match {
      case HttpResponse(status, headers, entity, _) if status.isSuccess() && !status.isRedirection() =>
        Future.successful((entity, headers))
      case HttpResponse(code, _, entity, _) =>
        Unmarshal(entity).to[String].map { err =>
          throw new S3Exception(err, code)
        }
    }
  }

  private[impl] def createPartitions(chunkSize: Int,
                                     sourceLocation: S3Location)(objectSize: Long): List[CopyPartition] =
    if (objectSize <= 0 || objectSize < chunkSize) CopyPartition(1, sourceLocation) :: Nil
    else {
      ((0L until objectSize by chunkSize).toList :+ objectSize)
        .sliding(2)
        .toList
        .zipWithIndex
        .map {
          case (ls, index) => CopyPartition(index + 1, sourceLocation, Some(ByteRange(ls.head, ls.last)))
        }
    }

  private def createCopyRequests(
      location: S3Location,
      sourceVersionId: Option[String],
      contentType: ContentType,
      s3Headers: S3Headers,
      partitions: Source[List[CopyPartition], NotUsed]
  )(parallelism: Int) = {
    val requestInfo: Source[(MultipartUpload, Int), NotUsed] =
      initiateUpload(location, contentType, s3Headers.headersFor(InitiateMultipartUpload))

    val headers = s3Headers.serverSideEncryption.toIndexedSeq.flatMap(_.headersFor(CopyPart))

    Source
      .setup { (mat, attr) =>
        implicit val conf = resolveSettings(attr, mat.system)

        requestInfo
          .zipWith(partitions) {
            case ((upload, _), ls) =>
              ls.map { cp =>
                val multipartCopy = MultipartCopy(upload, cp)
                val request = uploadCopyPartRequest(multipartCopy, sourceVersionId, headers)
                (request, multipartCopy)
              }
          }
          .mapConcat(identity)
          .flatMapConcat {
            case (req, info) => Signer.signedRequest(req, signingKey).zip(Source.single(info))
          }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  private def processUploadCopyPartRequests(
      requests: Source[(HttpRequest, MultipartCopy), NotUsed]
  )(parallelism: Int) =
    Source
      .setup { (mat, attr) =>
        implicit val materializer = mat
        import mat.executionContext
        implicit val sys = mat.system
        implicit val settings = resolveSettings(attr, mat.system)

        requests
          .via(superPool[MultipartCopy])
          .map {
            case (Success(r), multipartCopy) =>
              val entity = r.entity
              val upload = multipartCopy.multipartUpload
              val index = multipartCopy.copyPartition.partNumber
              import StatusCodes._
              r.status match {
                case OK =>
                  Unmarshal(entity).to[CopyPartResult].map(cp => SuccessfulUploadPart(upload, index, cp.eTag))
                case statusCode: StatusCode =>
                  Unmarshal(entity).to[String].map { err =>
                    val response =
                      Option(err).getOrElse(s"Failed to upload part into S3, status code was: $statusCode")
                    throw new S3Exception(response, statusCode)
                  }
              }

            case (Failure(ex), multipartCopy) =>
              Future.successful(
                FailedUploadPart(multipartCopy.multipartUpload, multipartCopy.copyPartition.partNumber, ex)
              )
          }
          .mapAsync(parallelism)(identity)
      }

  private def resolveSettings(attr: Attributes, sys: ActorSystem) =
    attr
      .get[S3SettingsValue]
      .map(_.settings)
      .getOrElse {
        val configPath = attr.get[S3SettingsPath](S3SettingsPath.Default).path
        S3Ext(sys).settings(configPath)
      }
}
