package com.xsn.explorer.services

import com.xsn.explorer.config.RPCConfig
import com.xsn.explorer.errors._
import com.xsn.explorer.helpers.{DataHelper, Executors, TransactionLoader}
import com.xsn.explorer.models.{Address, Blockhash}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalactic.Bad
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import play.api.libs.json.{JsNull, JsValue, Json}
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import scala.concurrent.Future

class XSNServiceRPCImplSpec extends WordSpec with MustMatchers with ScalaFutures with MockitoSugar with OptionValues {

  import DataHelper._

  val ws = mock[WSClient]
  val ec = Executors.externalServiceEC
  val config = new RPCConfig {
    override def password: RPCConfig.Password = RPCConfig.Password("pass")
    override def host: RPCConfig.Host = RPCConfig.Host("localhost")
    override def username: RPCConfig.Username = RPCConfig.Username("user")
  }

  val request = mock[WSRequest]
  val response = mock[WSResponse]
  when(ws.url(anyString)).thenReturn(request)
  when(request.withAuth(anyString(), anyString(), any())).thenReturn(request)
  when(request.withHttpHeaders(any())).thenReturn(request)

  val service = new XSNServiceRPCImpl(ws, config)(ec)

  def createRPCSuccessfulResponse(result: JsValue): String = {
    s"""
       |{
       |  "result": ${Json.prettyPrint(result)},
       |  "id": null,
       |  "error": null
       |}
     """.stripMargin
  }

  def createRPCErrorResponse(errorCode: Int, message: String): String = {
    s"""
       |{
       |  "result": null,
       |  "id": null,
       |  "error": {
       |    "code": $errorCode,
       |    "message": "$message"
       |  }
       |}
     """.stripMargin
  }

  "getTransaction" should {
    "handle coinbase" in {
      val txid = createTransactionId("024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c")
      val responseBody = createRPCSuccessfulResponse(TransactionLoader.json(txid.string))
      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        result.isGood mustEqual true

        val tx = result.get
        tx.id.string mustEqual "024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
        tx.vin.isEmpty mustEqual true
        tx.vout.size mustEqual 1
      }
    }

    "handle non-coinbase result" in {
      val txid = createTransactionId("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")
      val responseBody = createRPCSuccessfulResponse(TransactionLoader.json(txid.string))

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        result.isGood mustEqual true

        val tx = result.get
        tx.id.string mustEqual txid.string
        tx.vin.size mustEqual 1
        tx.vin.head.txid.string mustEqual "585cec5009c8ca19e83e33d282a6a8de65eb2ca007b54d6572167703768967d9"
        tx.vout.size mustEqual 3
      }
    }

    "handle transaction not found" in {
      val txid = createTransactionId("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")
      val responseBody = createRPCErrorResponse(-5, "No information available about transaction")
      val json = Json.parse(responseBody)
      when(response.status).thenReturn(500)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(TransactionNotFoundError).accumulating
      }
    }

    "handle error with message" in {
      val errorMessage = "Params must be an array"
      val txid = createTransactionId("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")
      val responseBody = createRPCErrorResponse(-32600, errorMessage)
      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        val error = XSNMessageError(errorMessage)
        result mustEqual Bad(error).accumulating
      }
    }

    "handle non successful status" in {
      val txid = createTransactionId("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")

      when(response.status).thenReturn(403)
      when(response.json).thenReturn(JsNull)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(XSNUnexpectedResponseError).accumulating
      }
    }

    "handle unexpected error" in {
      val txid = createTransactionId("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")

      val responseBody = """{"result":null,"error":{},"id":null}"""
      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(XSNUnexpectedResponseError).accumulating
      }
    }
  }

  "getAddressBalance" should {
    "return the balance" in {
      val responseBody =
        """
          |{
          |    "result": {
          |      "balance": 2465010000000000,
          |      "received": 1060950100000000
          |    },
          |    "error": null,
          |    "id": null
          |}
        """.stripMargin.trim

      val address = Address.from("Xi3sQfMQsy2CzMZTrnKW6HFGp1VqFThdLw").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getAddressBalance(address)) { result =>
        result.isGood mustEqual true

        val balance = result.get
        balance.balance mustEqual BigDecimal("24650100.00000000")
        balance.received mustEqual BigDecimal("10609501.00000000")
      }
    }

    "fail on invalid address" in {
      val responseBody = createRPCErrorResponse(-5, "Invalid address")
      val address = Address.from("Xi3sQfMQsy2CzMZTrnKW6HFGp1VqFThdLW").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getAddressBalance(address)) { result =>
        result mustEqual Bad(AddressFormatError).accumulating
      }
    }
  }

  "getTransactions" should {
    "return the transactions" in {
      val responseBody =
        """
          |{
          |    "result": [
          |      "3963203e8ff99c0effbc7c90ef1b534f7e60d9d4d1d131375bc73eb6af8b62d0",
          |      "56eff1fc3ec29277a039944a10826e1bd24685bec5e5c46c946846cb859dc14b",
          |      "d5718b83b6cec30e075c20dc61005a25a6eb707f14b92e89c2a9c4bc39635b5d",
          |      "9cd1d22e786b7b8722ce51f551c3c5af2053a52bd7694b9ef79e0a5d95053b19",
          |      "1dbf0277891ed39f8175fa08844eadbb6ed4b28464fbac0ba88464001192d79e",
          |      "2520ec229a76db8efa8e3b384582ac5c1969224a97f32475b957151f0c8cdfa7",
          |      "46d2f51afeab7aeb7adab821c374c7348ae0ff4edb7d0c9af995360630194cc8",
          |      "1a91406280e2a77dc0baf8a13491a977cba2d2dae6a8ba93fc6bbd3a7aeec4e5",
          |      "def0ae8bbfa45dca177f9c9f169e362bd25dee460a8ddc8c662e92e6968cd6d8"
          |    ],
          |    "error": null,
          |    "id": null
          |}
        """.stripMargin.trim

      val address = Address.from("Xi3sQfMQsy2CzMZTrnKW6HFGp1VqFThdLw").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransactions(address)) { result =>
        result.isGood mustEqual true
        result.get.size mustEqual 9
      }
    }

    "fail on invalid address" in {
      val responseBody = """{"result":null,"error":{"code":-5,"message":"Invalid address"},"id":null}"""

      val address = Address.from("Xi3sQfMQsy2CzMZTrnKW6HFGp1VqFThdLW").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getTransactions(address)) { result =>
        result mustEqual Bad(AddressFormatError).accumulating
      }
    }
  }

  "getBlock" should {
    "return a block" in {
      val responseBody =
        """
          |{
          |    "result": {
          |      "hash": "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81",
          |      "confirmations": 11163,
          |      "size": 478,
          |      "height": 809,
          |      "version": 536870912,
          |      "merkleroot": "598cc6ba8238d87641b0dbd02485b7d635b5417429df3145c98c3ff8779ab4b8",
          |      "tx": [
          |        "7f12adbb63d443502cf151c76946d5faa0b1c662a5d67afc7da085c74e06f1ce",
          |        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
          |      ],
          |      "time": 1520318120,
          |      "mediantime": 1520318054,
          |      "nonce": 0,
          |      "bits": "1d011212",
          |      "difficulty": 0.9340526210769362,
          |      "chainwork": "00000000000000000000000000000000000000000000000000000084c71ff420",
          |      "previousblockhash": "9490ce5d14bb5e79a790ddede03fc3a9bde3f7156f34a57ae3ceb56ae7426c14",
          |      "nextblockhash": "f6e3199c241131e79640fe027a6ef993c02b3520c3d4ba08cd67abfbb98ec07e"
          |    },
          |    "error": null,
          |    "id": null
          |}
          |""".stripMargin

      val blockhash = Blockhash.from("b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getBlock(blockhash)) { result =>
        result.isGood mustEqual true

        val block = result.get
        block.hash.string mustEqual "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
        block.transactions.size mustEqual 2
      }
    }

    "fail on unknown block" in {
      val responseBody = """{"result":null,"error":{"code":-5,"message":"Block not found"},"id":null}"""

      val blockhash = Blockhash.from("b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b80").get

      val json = Json.parse(responseBody)

      when(response.status).thenReturn(200)
      when(response.json).thenReturn(json)
      when(request.post[String](anyString())(any())).thenReturn(Future.successful(response))

      whenReady(service.getBlock(blockhash)) { result =>
        result mustEqual Bad(BlockNotFoundError).accumulating
      }
    }
  }
}
