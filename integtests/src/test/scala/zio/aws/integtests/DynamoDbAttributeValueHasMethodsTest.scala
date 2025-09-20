package zio.aws.integtests

import zio._
import zio.test._
import zio.test.Assertion._
import zio.aws.dynamodb.model._
import zio.aws.dynamodb.model.primitives._
import software.amazon.awssdk.services.dynamodb.{model => aws}

/** CI Test Suite for verifying the has* methods in generated DynamoDB
  * AttributeValue code
  *
  * This test directly uses the actual generated DynamoDB AttributeValue class
  * to verify that GitHub issue #1626 has been properly addressed by the code
  * generator.
  *
  * These tests prove that:
  *   1. The has* methods are actually present in the generated code
  *   2. They work correctly with real AWS SDK AttributeValue instances
  *   3. The behavior matches the underlying AWS SDK exactly
  */
object DynamoDbAttributeValueHasMethodsTest extends ZIOSpecDefault {

  def spec = suite("DynamoDB AttributeValue has* methods (Generated Code)")(
    suite("hasL method verification")(
      test("hasL returns true for List attributes") {
        val awsListValue = aws.AttributeValue
          .builder()
          .l(aws.AttributeValue.builder().s("item").build())
          .build()

        val zioValue: AttributeValue.ReadOnly =
          AttributeValue.wrap(awsListValue)

        assertTrue(
          zioValue.hasL,
          !zioValue.hasM,
          !zioValue.hasSs
        )
      },
      test("hasL returns true for empty List") {
        val awsEmptyListValue = aws.AttributeValue
          .builder()
          .l(Seq.empty[aws.AttributeValue]: _*)
          .build()

        val zioValue = AttributeValue.wrap(awsEmptyListValue)

        assertTrue(zioValue.hasL)
      },
      test("hasL returns false for non-List attributes") {
        val awsStringValue = aws.AttributeValue.builder().s("test").build()
        val awsMapValue = aws.AttributeValue
          .builder()
          .m(
            java.util.Map
              .of("key", aws.AttributeValue.builder().s("value").build())
          )
          .build()

        val zioStringValue = AttributeValue.wrap(awsStringValue)
        val zioMapValue = AttributeValue.wrap(awsMapValue)

        assertTrue(!zioStringValue.hasL, !zioMapValue.hasL)
      }
    ),
    suite("hasM method verification")(
      test("hasM returns true for Map attributes") {
        val awsMapValue = aws.AttributeValue
          .builder()
          .m(
            java.util.Map.of(
              "key1",
              aws.AttributeValue.builder().s("value1").build(),
              "key2",
              aws.AttributeValue.builder().s("value2").build()
            )
          )
          .build()

        val zioValue = AttributeValue.wrap(awsMapValue)

        assertTrue(zioValue.hasM, !zioValue.hasL, !zioValue.hasSs)
      },
      test("hasM returns true for empty Map") {
        val awsEmptyMapValue = aws.AttributeValue
          .builder()
          .m(java.util.Map.of())
          .build()

        val zioValue = AttributeValue.wrap(awsEmptyMapValue)

        assertTrue(zioValue.hasM)
      },
      test("hasM returns false for non-Map attributes") {
        val awsStringValue = aws.AttributeValue.builder().s("test").build()
        val awsListValue = aws.AttributeValue
          .builder()
          .l(aws.AttributeValue.builder().s("item").build())
          .build()

        val zioStringValue = AttributeValue.wrap(awsStringValue)
        val zioListValue = AttributeValue.wrap(awsListValue)

        assertTrue(!zioStringValue.hasM, !zioListValue.hasM)
      }
    ),
    suite("hasSs method verification")(
      test("hasSs returns true for String Set attributes") {
        val awsStringSetValue = aws.AttributeValue
          .builder()
          .ss("value1", "value2", "value3")
          .build()

        val zioValue = AttributeValue.wrap(awsStringSetValue)

        assertTrue(
          zioValue.hasSs,
          !zioValue.hasNs,
          !zioValue.hasBs,
          !zioValue.hasL,
          !zioValue.hasM
        )
      },
      test("hasSs returns false for non-StringSet attributes") {
        val awsListValue = aws.AttributeValue
          .builder()
          .l(aws.AttributeValue.builder().s("item").build())
          .build()
        val awsNumberSetValue = aws.AttributeValue
          .builder()
          .ns("123", "456")
          .build()

        val zioListValue = AttributeValue.wrap(awsListValue)
        val zioNumberSetValue = AttributeValue.wrap(awsNumberSetValue)

        assertTrue(!zioListValue.hasSs, !zioNumberSetValue.hasSs)
      }
    ),
    suite("hasNs method verification")(
      test("hasNs returns true for Number Set attributes") {
        val awsNumberSetValue = aws.AttributeValue
          .builder()
          .ns("123", "456", "789")
          .build()

        val zioValue = AttributeValue.wrap(awsNumberSetValue)

        assertTrue(
          zioValue.hasNs,
          !zioValue.hasSs,
          !zioValue.hasBs,
          !zioValue.hasL,
          !zioValue.hasM
        )
      },
      test("hasNs returns false for non-NumberSet attributes") {
        val awsStringSetValue = aws.AttributeValue
          .builder()
          .ss("value1", "value2")
          .build()
        val awsMapValue = aws.AttributeValue
          .builder()
          .m(
            java.util.Map
              .of("key", aws.AttributeValue.builder().s("value").build())
          )
          .build()

        val zioStringSetValue = AttributeValue.wrap(awsStringSetValue)
        val zioMapValue = AttributeValue.wrap(awsMapValue)

        assertTrue(!zioStringSetValue.hasNs, !zioMapValue.hasNs)
      }
    ),
    suite("hasBs method verification")(
      test("hasBs returns true for Binary Set attributes") {
        val awsBinarySetValue = aws.AttributeValue
          .builder()
          .bs(
            software.amazon.awssdk.core.SdkBytes.fromUtf8String("binary1"),
            software.amazon.awssdk.core.SdkBytes.fromUtf8String("binary2")
          )
          .build()

        val zioValue = AttributeValue.wrap(awsBinarySetValue)

        assertTrue(
          zioValue.hasBs,
          !zioValue.hasNs,
          !zioValue.hasSs,
          !zioValue.hasL,
          !zioValue.hasM
        )
      },
      test("hasBs returns false for non-BinarySet attributes") {
        val awsStringSetValue = aws.AttributeValue
          .builder()
          .ss("value1", "value2")
          .build()
        val awsNumberSetValue = aws.AttributeValue
          .builder()
          .ns("123", "456")
          .build()

        val zioStringSetValue = AttributeValue.wrap(awsStringSetValue)
        val zioNumberSetValue = AttributeValue.wrap(awsNumberSetValue)

        assertTrue(!zioStringSetValue.hasBs, !zioNumberSetValue.hasBs)
      }
    ),
    suite("AWS SDK consistency verification")(
      test("ZIO has* methods match AWS SDK has* methods exactly") {
        val awsListValue = aws.AttributeValue
          .builder()
          .l(aws.AttributeValue.builder().s("item").build())
          .build()
        val awsMapValue = aws.AttributeValue
          .builder()
          .m(
            java.util.Map
              .of("key", aws.AttributeValue.builder().s("value").build())
          )
          .build()
        val awsStringSetValue =
          aws.AttributeValue.builder().ss("value1").build()
        val awsNumberSetValue = aws.AttributeValue.builder().ns("123").build()
        val awsBinarySetValue = aws.AttributeValue
          .builder()
          .bs(software.amazon.awssdk.core.SdkBytes.fromUtf8String("binary"))
          .build()

        val zioListValue = AttributeValue.wrap(awsListValue)
        val zioMapValue = AttributeValue.wrap(awsMapValue)
        val zioStringSetValue = AttributeValue.wrap(awsStringSetValue)
        val zioNumberSetValue = AttributeValue.wrap(awsNumberSetValue)
        val zioBinarySetValue = AttributeValue.wrap(awsBinarySetValue)

        // Verify ZIO methods exactly match AWS SDK methods
        assertTrue(
          zioListValue.hasL == awsListValue.hasL(),
          zioListValue.hasM == awsListValue.hasM(),
          zioMapValue.hasM == awsMapValue.hasM(),
          zioMapValue.hasL == awsMapValue.hasL(),
          zioStringSetValue.hasSs == awsStringSetValue.hasSs(),
          zioNumberSetValue.hasNs == awsNumberSetValue.hasNs(),
          zioBinarySetValue.hasBs == awsBinarySetValue.hasBs()
        )
      }
    ),
    suite("Empty vs null distinction")(
      test("can distinguish empty collections from null using has* methods") {
        val emptyList = aws.AttributeValue
          .builder()
          .l(Seq.empty[aws.AttributeValue]: _*)
          .build()
        val emptyMap = aws.AttributeValue
          .builder()
          .m(java.util.Map.of())
          .build()
        val nullValue = aws.AttributeValue
          .builder()
          .nul(true)
          .build()

        val zioEmptyList = AttributeValue.wrap(emptyList)
        val zioEmptyMap = AttributeValue.wrap(emptyMap)
        val zioNullValue = AttributeValue.wrap(nullValue)

        // Empty collections should have their type, null should not
        assertTrue(
          zioEmptyList.hasL, // empty list has L type
          zioEmptyMap.hasM, // empty map has M type
          !zioNullValue.hasL, // null doesn't have L type
          !zioNullValue.hasM // null doesn't have M type
        )
      }
    ),
    test(
      "GitHub issue #1626 regression test - all has* methods exist and work"
    ) {
      // This test ensures that all the methods requested in the GitHub issue are present
      // and working correctly. If this test compiles and passes, the issue is resolved.

      val awsValue = aws.AttributeValue
        .builder()
        .l(aws.AttributeValue.builder().s("test").build())
        .build()

      val zioValue = AttributeValue.wrap(awsValue)

      // These method calls will fail compilation if the methods don't exist
      val hasLResult: Boolean = zioValue.hasL
      val hasMResult: Boolean = zioValue.hasM
      val hasSsResult: Boolean = zioValue.hasSs
      val hasNsResult: Boolean = zioValue.hasNs
      val hasBsResult: Boolean = zioValue.hasBs

      // Verify the methods return correct results
      assertTrue(
        hasLResult == true, // List attribute
        hasMResult == false, // Not a Map
        hasSsResult == false, // Not a String Set
        hasNsResult == false, // Not a Number Set
        hasBsResult == false // Not a Binary Set
      )
    }
  )
}
